# Nhận xét kết quả thí nghiệm

1. Khi tỉ lệ mất gói tăng, thời gian truyền và số lần gửi lại nhìn chung tăng.
2. Nguyên nhân là ACK bị thiếu làm TCP phải retransmit và chờ các lần timeout.
3. Cửa sổ `window=1` có throughput thấp vì chỉ cho phép một segment đang bay.
4. `window=4` và `window=32` tận dụng đường truyền tốt hơn khi RTT ổn định.
5. Tuy nhiên cửa sổ lớn cũng có thể tạo nhiều ACK trùng lặp khi một segment bị mất.
6. ACK trùng lặp giúp fast retransmit phục hồi nhanh hơn so với chờ RTO.
7. Khi congestion control bật, cwnd giảm sau timeout để tránh làm nghẽn mạng thêm.
8. Vì vậy throughput có thể giảm mạnh ở các mức mất gói cao nhưng ổn định hơn.
9. Khi congestion control tắt, cửa sổ cấu hình được giữ lớn hơn và có thể truyền nhanh hơn.
10. Đổi lại, chế độ này tạo nhiều retransmission hơn khi mạng đang mất gói.
11. RTO thích nghi giúp tránh chờ cố định 1 giây trong mạng có RTT nhỏ.
12. Nó cũng giảm timeout giả khi RTT tăng, nhờ cập nhật theo SRTT và RTTVAR.
13. Các giá trị trung bình trong CSV giúp giảm ảnh hưởng của ngẫu nhiên giữa ba lần chạy.
14. Cần đối chiếu thêm cột `data_check` để chỉ dùng các phiên truyền dữ liệu đúng.
