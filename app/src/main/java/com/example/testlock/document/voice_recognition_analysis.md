flowchart TD

    %% =========================
    %% INPUT
    %% =========================

    MIC["Sau khi đã mở flow AI"]

    AR["AudioRecord
    PCM audio"]

    MIC --> AR


    %% =========================
    %% SPEECH DETECTION
    %% =========================

    VAD["Silero VAD
    Voice Activity Detection"]

    AR --> VAD

    VAD -->|"Có tiếng người"| SPEECH["Speech audio"]
    VAD -->|"Không có tiếng người"| NOSPEECH["Ignore / No speech"]


    %% =========================
    %% SPEAKER RECOGNITION
    %% =========================

    SPEAKER["3D-Speaker
    Speaker Embedding
    "đây có phải là giọng đặc trưng của người A không""]

    SPEECH --> SPEAKER

    SPEAKER --> EMB["Speaker embedding"]

    EMB --> VERIFY["Speaker Verification
    Có phải đúng người nói?"]


    %% =========================
    %% ASR
    %% =========================

    ZIP["Zipformer
    Offline ASR
    Lời nói -> văn bảnh"]

    SPEECH --> ZIP

    ZIP --> TEXT["Recognized text"]


    %% =========================
    %% KEYWORD
    %% =========================

    TEXT --> KEYWORD["Keyword detection"]

    KEYWORD --> MATCH["Keyword matching"]

    MATCH -->|"Matched"| COMMAND["Command / Intent"]


    %% =========================
    %% AI
    %% =========================




    %% =========================
    %% ANDROID SPEECHRECOGNIZER
    %% =========================
    SR["Android SpeechR23hecognizer"]
    SR["Android SpeechRecognizer"]

    SR -->|"Cloud / system ASR"| SR_TEXT["Recognized text"]

    SR_TEXT --> KEYWORD


    %% =========================
    %% STYLES
    %% =========================

    classDef input fill:#e3f2fd,stroke:#1565c0,stroke-width:2px;
    classDef audio fill:#fff3e0,stroke:#ef6c00,stroke-width:2px;
    classDef model fill:#e8f5e9,stroke:#2e7d32,stroke-width:2px;
    classDef result fill:#f3e5f5,stroke:#7b1fa2,stroke-width:2px;
    classDef ai fill:#fce4ec,stroke:#c2185b,stroke-width:2px;

    class MIC,AR input;
    class VAD,SPEECH audio;
    class SPEAKER,ZIP,SR model;
    class EMB,VERIFY,TEXT,KEYWORD,MATCH,SR_TEXT result;
    class AI,COMMAND,RESPONSE ai;