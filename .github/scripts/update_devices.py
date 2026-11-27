import csv
import json
import time
from datetime import date, datetime, timedelta, timezone
from io import StringIO
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import matplotlib as mpl
from matplotlib.figure import Figure
from matplotlib.ticker import MaxNLocator


STATS = Path(__file__).resolve().parents[2] / "stats"
COUNTER = "https://logging.tw1.ru/api.php?action=public"
LOCAL_TZ = timezone(timedelta(hours=5))
DAY = timedelta(days=1)


def fetch_total():
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
    if payload.get("ok") is not True or type(total) is not int or total < 0:
        raise ValueError("Invalid public device count")
    return total


def render(history, destination):
    first, last = min(history), max(history)
    span = (last - first).days
    dates = [first + i * DAY for i in range(span + 1)]
    # Leave a gap if a scheduled observation is missing.
    totals = [history.get(day, float("nan")) for day in dates]
    ink, muted, grid = "#302431", "#615568", "#ded9db"
    fig = Figure(figsize=(9, 3.15), facecolor="#fffaf4")
    ax = fig.add_axes([0.08, 0.2, 0.86, 0.60], facecolor=fig.get_facecolor())
    fig.text(0.08, 0.88, f"Всего устройств: {history[last]}", color=ink, weight="bold", size=19)
    fig.text(0.94, 0.89, last.strftime("%d.%m.%Y"), color=muted, size=12, ha="right")
    ax.plot(dates, totals, color="#77466b", linewidth=2.3, marker=".", markersize=4)
    ax.fill_between(dates, totals, color="#eadce4", alpha=0.65)
    ax.scatter([last], [history[last]], color="#b34044", s=40, zorder=3, clip_on=False)
    ax.set_ylim(0, max(5, max(history.values()) * 1.12))
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
        fig.savefig(svg, format="svg", metadata={"Date": None, "Title": "Общее число устройств ReManga по датам"})
        lines = (line.rstrip() for line in svg.getvalue().splitlines())
        destination.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def main():
    path = STATS / "devices.csv"
    with path.open(encoding="utf-8", newline="") as f:
        history = {date.fromisoformat(row["date"]): int(row["total"]) for row in csv.DictReader(f)}
    total = fetch_total()
    today = datetime.now(LOCAL_TZ).date()
    if today < max(history) or total < history[max(history)]:
        raise ValueError("Snapshot predates the history or the cumulative count decreased")
    history[today] = total
    render(history, STATS / "devices.svg")
    with path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f, lineterminator="\n")
        writer.writerow(("date", "total"))
        writer.writerows(sorted(history.items()))
    print(f"{today}: {total} устройств")


if __name__ == "__main__":
    main()
