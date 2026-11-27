import csv
import json
import time
from datetime import date, datetime, timedelta, timezone
from io import StringIO
from pathlib import Path
from typing import NamedTuple
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import matplotlib as mpl
from matplotlib.figure import Figure
from matplotlib.ticker import MaxNLocator


STATS = Path(__file__).resolve().parents[2] / "stats"
COUNTER = "https://logging.tw1.ru/api.php?action=public"
LOCAL_TZ = timezone(timedelta(hours=5))
DAY = timedelta(days=1)


class DeviceCounts(NamedTuple):
    total: int
    active24h: int | None


def fetch_counts():
    request = Request(COUNTER, headers={"Accept": "application/json", "Cache-Control": "no-cache"})
    for delay in (0, 1, 5):
        time.sleep(delay)
        try:
            with urlopen(request, timeout=15) as response:
                payload = json.load(response)
            break
        except HTTPError as exc:
            if delay == 5 or (exc.code not in (408, 429) and exc.code < 500):
                raise
        except OSError:
            if delay == 5:
                raise
    total = payload.get("total")
    active = payload.get("active24h")
    if payload.get("ok") is not True or type(total) is not int or total < 0:
        raise ValueError("Invalid public device count")
    if type(active) is not int or not 0 <= active <= total:
        raise ValueError("Invalid active device count")
    return DeviceCounts(total, active)


def render(history, destination):
    first, last = min(history), max(history)
    span = (last - first).days
    dates = [first + i * DAY for i in range(span + 1)]
    # Leave a gap if a scheduled observation is missing.
    totals = [history[day].total if day in history else None for day in dates]
    active = [history[day].active24h if day in history else None for day in dates]
    latest = history[last]
    ink, muted, grid = "#302431", "#615568", "#ded9db"
    fig = Figure(figsize=(9, 3.6), facecolor="#fffaf4")
    ax = fig.add_axes([0.08, 0.16, 0.86, 0.54], facecolor=fig.get_facecolor())
    fig.text(0.08, 0.88, "Устройства ReManga", color=ink, weight="bold", size=19)
    fig.text(0.94, 0.89, last.strftime("%d.%m.%Y"), color=muted, size=12, ha="right")
    ax.plot(dates, totals, color="#77466b", linewidth=2.3, marker=".", markersize=5,
            label=f"Всего: {latest.total}")
    active_label = "Активны за 24 часа"
    if latest.active24h is not None:
        active_label += f": {latest.active24h}"
    ax.plot(dates, active, color="#006e68", linewidth=2.1, linestyle="--", marker="o", markersize=4,
            label=active_label)
    ax.legend(loc="lower left", bbox_to_anchor=(0, 1.04), borderaxespad=0, ncols=2,
              frameon=False, fontsize=11, labelcolor=ink)
    ax.set_ylim(0, max(5, max(counts.total for counts in history.values()) * 1.12))
    ax.yaxis.set_major_locator(MaxNLocator(nbins=4, integer=True))
    ax.grid(axis="y", color=grid, linewidth=0.8)
    ax.set_axisbelow(True)
    ax.spines[:].set_visible(False)
    ax.tick_params(axis="both", colors=muted, labelsize=10, length=0, pad=6)
    ticks = [dates[i] for i in sorted({round(span * n / 4) for n in range(5)})]
    fmt = "%d.%m" if first.year == last.year else "%d.%m.%y"
    ax.set_xticks(ticks, [d.strftime(fmt) for d in ticks])
    if span == 0:
        ax.set_xlim(first - DAY, first + DAY)
    with StringIO() as svg, mpl.rc_context({"svg.hashsalt": "remanga-devices"}):
        fig.savefig(svg, format="svg", metadata={"Date": None, "Title": "Устройства ReManga: всего и активные за 24 часа"})
        lines = (line.rstrip() for line in svg.getvalue().splitlines())
        destination.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def main():
    path = STATS / "devices.csv"
    with path.open(encoding="utf-8", newline="") as f:
        history = {
            date.fromisoformat(row["date"]): DeviceCounts(
                int(row["total"]), int(row["active24h"]) if row.get("active24h") else None
            )
            for row in csv.DictReader(f)
        }
    counts = fetch_counts()
    today = datetime.now(LOCAL_TZ).date()
    if today < max(history) or counts.total < history[max(history)].total:
        raise ValueError("Snapshot predates the history or the cumulative count decreased")
    history[today] = counts
    render(history, STATS / "devices.svg")
    with path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f, lineterminator="\n")
        writer.writerow(("date", "total", "active24h"))
        writer.writerows((day, *history[day]) for day in sorted(history))
    print(f"{today}: всего {counts.total}, активны за 24 часа {counts.active24h}")


if __name__ == "__main__":
    main()
