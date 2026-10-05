# Phân tích Công nghệ & Luồng Xử lý Âm thanh/Giọng nói trong AIvoicelock

Tài liệu này tổng hợp các công nghệ, kiến trúc và sơ đồ luồng xử lý nhận diện giọng nói (Voice Recognition & Speaker Verification) trong ứng dụng **AIvoicelock**.

## 1. Các Công nghệ & Thư viện Cốt lõi

| Thành phần | Công nghệ / Thư viện | Vai trò trong App |
| :--- | :--- | :--- |
| **AI Inference Engine** | **Sherpa-onnx** (`com.k2fsa.sherpa.onnx`) | Thư viện chạy mô hình ONNX trên thiết bị (On-device) qua JNI (`libsherpa-onnx-jni.so`). |
| **Speaker Model** | **ERes2Net** (`3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx`) | Mô hình AI mã nguồn mở từ 3D-Speaker, trích xuất đặc trưng giọng nói thành véc-tơ **512 chiều** (Embedding). |
| **Voice Activity (VAD)** | **Silero VAD / Sherpa VAD** | Phát hiện đoạn có tiếng nói, cắt bỏ khoảng lặng và tiếng ồn nền. |
| **Audio Capture** | **Android `AudioRecord` & `LockScreenService`** | Dịch vụ nền (Foreground Service) thu âm thanh chuẩn 16kHz từ microphone khi màn hình khóa. |
| **Database & State** | **Room Database** (`AppDatabase`) | Lưu trữ mẫu giọng nói đã đăng ký (`TextNeedSayWithEmbedding`, `EmbeddingWithFilePath`). |
| **Verification Method** | **Cosine Similarity / Threshold** | So khớp véc-tơ embedding giữa giọng nói thực tế và mẫu đăng ký (`SpeakerEmbeddingManager`). |

---

## 2. Bản chất Cơ chế Nhận diện (Hybrid Verification)

App sử dụng phương pháp **Text-Dependent Speaker Verification (Xác thực người nói phụ thuộc văn bản)**, kết hợp đồng thời 2 yếu tố:
1. **Speaker Verification (Sinh trắc học giọng nói):** Kiểm tra âm sắc, đặc điểm vòm họng qua véc-tơ 512 chiều của ERes2Net để xác định đúng chủ nhân.
2. **Voice Command (Nội dung câu lệnh):** Kiểm tra xem người dùng có nói đúng câu khẩu lệnh/từ khóa đã đăng ký hay không (`textRecognized`).

---

## 3. Sơ đồ Luồng Xử lý Giọng nói (Architecture & Workflow)

```mermaid
flowchart TD
    subgraph Setup ["1. Quá trình Đăng ký (Enrollment)"]
        A1[User ghi âm câu lệnh mẫu] --> A2[Android AudioRecord (16kHz)]
        A2 --> A3[Sherpa VAD (Lọc khoảng lặng)]
        A3 --> A4[SpeakerEmbeddingExtractor<br/>(ERes2Net .onnx model)]
        A4 --> A5[Tạo Vector Embedding (512-dim)]
        A5 --> A6[Lưu vào Room Database<br/>(Text + Embedding + Audio File)]
    end

    subgraph Unlock ["2. Quá trình Mở khóa (Unlock Verification)"]
        B1[Màn hình khóa / LockScreenService] --> B2[User nói câu khẩu lệnh]
        B2 --> B3[Android AudioRecord]
        B3 --> B4[Sherpa VAD]
        B4 --> B5[SpeakerEmbeddingExtractor<br/>(ERes2Net .onnx model)]
        B5 --> B6[Vector Embedding mới (512-dim)]
        B6 --> B7{SpeakerEmbeddingManager<br/>So khớp Cosine Similarity}
        B7 -->|Độ tương đồng >= Threshold| B8{Kiểm tra Nội dung<br/>Voice Command Text}
        B7 -->|Thấp hơn Threshold| B9[Từ chối mở khóa / Fallback PIN/Vân tay]
        B8 -->|Khớp từ khóa| B10[🔓 Mở khóa thành công (Unlock)]
        B8 -->|Sai từ khóa| B9
    end
```

---

## 4. Các File Mã nguồn Quan trọng

- **Sherpa-onnx Wrapper:** [SpeakerEmbeddingExtractor.java](file:///C:/code/jadx/AIvoicelock/app/src/main/java/com/k2fsa/sherpa/onnx/SpeakerEmbeddingExtractor.java), [SpeakerEmbeddingManager.java](file:///C:/code/jadx/AIvoicelock/app/src/main/java/com/k2fsa/sherpa/onnx/SpeakerEmbeddingManager.java), [SpeakerRecognition.java](file:///C:/code/jadx/AIvoicelock/app/src/main/java/com/k2fsa/sherpa/onnx/SpeakerRecognition.java).
- **Models & Repositories:** [TextNeedSayWithEmbedding.java](file:///C:/code/jadx/AIvoicelock/app/src/main/java/pion/tech/pionbase/framework/model/TextNeedSayWithEmbedding.java), [EmbeddingWithFilePath.java](file:///C:/code/jadx/AIvoicelock/app/src/main/java/pion/tech/pionbase/framework/model/EmbeddingWithFilePath.java).
- **Service & UI:** `LockScreenService.java`, `AudioVisualizerView.java`.
