"""Create report charts from test-results/ket_qua.csv."""
import csv
from collections import defaultdict
from pathlib import Path

import matplotlib.pyplot as plt


CSV_PATH = Path("test-results/ket_qua.csv")
OUT_DIR = CSV_PATH.parent


def load_rows():
    with CSV_PATH.open(newline="", encoding="utf-8-sig") as stream:
        return list(csv.DictReader(stream))


def grouped(rows, value):
    result = defaultdict(list)
    for row in rows:
        result[(int(row["loss_percent"]), int(row["window_size"]))].append(float(row[value]))
    return {key: sum(values) / len(values) for key, values in result.items()}


def plot_by_window(rows, value, ylabel, filename, title):
    values = grouped(rows, value)
    for window in (1, 4, 32):
        points = sorted((loss, value) for (loss, w), value in values.items() if w == window)
        plt.plot([p[0] for p in points], [p[1] for p in points], marker="o", label=f"window={window}")
    plt.xlabel("Mất gói (%)")
    plt.ylabel(ylabel)
    plt.title(title)
    plt.grid(True, alpha=0.3)
    plt.legend()
    plt.tight_layout()
    plt.savefig(OUT_DIR / filename, dpi=160)
    plt.close()


def main():
    rows = load_rows()
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    plot_by_window(rows, "transfer_ms", "Thời gian truyền (ms)",
                   "thoi_gian_theo_mat_goi.png", "Thời gian theo mất gói")
    plot_by_window(rows, "retransmissions", "Số lần gửi lại",
                   "gui_lai_theo_mat_goi.png", "Gửi lại theo mất gói")
    plot_by_window(rows, "throughput_bytes_per_sec", "Throughput (byte/s)",
                   "throughput_theo_mat_goi.png", "Throughput theo mất gói")
    print(f"Đã tạo 3 biểu đồ trong {OUT_DIR}")


if __name__ == "__main__":
    main()
