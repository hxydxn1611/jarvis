# Jarvis Personal Mobile Agent

A native Android autonomous personal assistant that runs directly on your device, capable of perceiving on-screen context, reading notifications, and executing gestures, taps, and app actions on command.

---

## 🏛 Architecture

```
                    ┌───────────────────────────┐
                    │      USER INTERACTION     │
                    │  - Voice Command via Mic  │
                    │  - Floating Quick Bubble  │
                    │  - Manual Text Console    │
                    └─────────────┬─────────────┘
                                  │
                                  ▼
                    ┌───────────────────────────┐
                    │      ASSISTANT BRAIN      │
                    │   (Dual-Mode Reasoning)   │
                    │                           │
                    │  Mode 1: Offline Engine   │
                    │  (Zero API key required)  │
                    │                           │
                    │  Mode 2: Cloud AI Engine  │
                    │  (Free Gemini 2.0 Flash)  │
                    └─────────────┬─────────────┘
                                  │
                                  ▼
      ┌────────────────────────────────────────────────────────┐
      │                   DEVICE EXECUTION                     │
      │                                                        │
      │  AssistantAccessibilityService                         │
      │  - Inspects on-screen view hierarchy and clickable UI  │
      │  - Injects gestures: clicks, text entry, and scrolls   │
      │  - Performs system actions (Home, Back, Screenshots)   │
      │                                                        │
      │  AssistantNotificationService                          │
      │  - Intercepts incoming messages & alerts               │
      │                                                        │
      │  VoiceManager                                          │
      │  - Dual STT (Speech-to-Text) and natural TTS audio     │
      └────────────────────────────────────────────────────────┘
```

---

## 🚀 How to Build & Install on Your Samsung Galaxy M34

### 1. Open in Android Studio
1. Open **Android Studio** on your PC.
2. Select **Open Project** and navigate to `C:\Users\Admin\Desktop\jarvis`.
3. Allow Gradle to synchronize dependencies.
4. Connect your Samsung Galaxy M34 via USB (with **USB Debugging** enabled in Developer Options).
5. Click **Run (`Shift + F10`)** to install and launch the app directly onto your phone.

### 2. Required One-Time Permissions on Samsung One UI
Once launched on your phone, tap the authorization buttons in order:
1. **Accessibility**: Tap *Enable Accessibility* > look for **Installed apps** / **Jarvis Assistant** > Turn it **ON**.
2. **Floating Overlay**: Allow permission to draw over other apps (creates the floating quick-access bubble).
3. **Notification Access**: Allow Jarvis to read incoming messages (WhatsApp, SMS, etc.).
4. **Microphone**: Allow audio recording for voice commands.

---

## 🎙 How to Use

* **Offline Voice Commands (No API Key Required)**:
  - *"Open WhatsApp"*
  - *"Open YouTube"*
  - *"Take a screenshot"*
  - *"Scroll down"*
  - *"Read screen"* (summarizes visible on-screen items aloud)
  - *"Check messages"* (reads recent notification alerts)
  - *"Go home"* / *"Go back"*

* **Super-Brain AI Mode (Optional)**:
  - Get a free Gemini API key from [Google AI Studio](https://aistudio.google.com).
  - Paste the key in the Jarvis App settings and tap **Save Key**.
  - Jarvis now uses multimodal reasoning to analyze complex multi-step screen flows and natural conversation.

