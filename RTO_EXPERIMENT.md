# Thí nghiệm RTO thích nghi

## Mục tiêu

So sánh hai phiên có cùng dữ liệu, mất/hỏng gói bằng 0, chỉ thay độ trễ mô phỏng:

```text
delay_min_ms=20,  delay_max_ms=20
delay_min_ms=300, delay_max_ms=300
```

Chạy lần lượt với cùng `window_size`, `message_repeat` và `congestion_control`. Trong GUI,
đường `SRTT` và `RTO` xuất hiện trên biểu đồ; báo cáo cuối phiên có dạng `RTO cuối / SRTT`.

## Kết quả kỳ vọng và giải thích

Với trễ 20 ms, mẫu RTT làm SRTT hội tụ gần 40 ms (mô phỏng trễ hai chiều), RTO bị chặn
ở mức tối thiểu 200 ms. So với RTO cố định 1000 ms, khi có mất gói phiên thích nghi
phát hiện timeout sớm hơn khoảng 800 ms.

Với trễ 300 ms mỗi chiều, SRTT hội tụ gần 600 ms và RTO ban đầu tăng lên xấp xỉ
`SRTT + 4*RTTVAR`; sau đó giảm dần theo các mẫu ổn định. RTO cố định 1000 ms
không nhất thiết timeout giả ở đúng kịch bản 300 ms ổn định, nhưng chờ lâu hơn cần
thiết so với RTO đã hội tụ và không thích ứng khi RTT tăng bởi hàng đợi/jitter.

Để thấy trường hợp gửi lại thừa rõ ràng, tăng trễ một chiều lên trên khoảng 500 ms
hoặc thêm jitter: RTT có thể vượt 1000 ms trong khi RTO cố định chưa đổi, còn RTO
thích nghi sẽ tăng theo mẫu RTT và chỉ backoff khi thật sự timeout.

Theo Karn, ACK của segment đã retransmit không được dùng làm mẫu RTT. Khi timeout,
RTO nhân đôi và bị giới hạn tối đa 5 s; ACK mới từ segment gửi lần đầu sẽ tính lại
RTO theo RFC 6298.
