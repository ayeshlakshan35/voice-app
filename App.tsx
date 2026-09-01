import React, { useCallback, useEffect, useRef, useState } from "react";
import {
  Alert,
  DeviceEventEmitter,
  NativeModules,
  PermissionsAndroid,
  Platform,
  SafeAreaView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
  Animated,
  Easing,
} from "react-native";
import { Ionicons } from "@expo/vector-icons";

const API_BASE = process.env.EXPO_PUBLIC_API_BASE;
const WS_URL = process.env.EXPO_PUBLIC_WS_URL;

const INPUT_SAMPLE_RATE = 16000;
const OUTPUT_SAMPLE_RATE = 24000;

type VoiceEvent = {
  type: string;
  data?: string;
  message?: string;
  query?: string;
  text?: string;
  sampleRate?: number;
};

type PcmAudioBridge = {
  enqueue(base64Pcm: string, sampleRate: number): void;
  clear(): void;
  release(): void;
  startInput(): void;
  stopInput(): void;
};

type NativeInputEvent = {
  data: string;
  bytes: number;
};

const pcmAudio = NativeModules.PcmAudio as PcmAudioBridge | undefined;

export default function App() {
  const [status, setStatus] = useState("Idle");
  const [isConnected, setIsConnected] = useState(false);
  const [isListening, setIsListening] = useState(false);
  const [isSpeaking, setIsSpeaking] = useState(false);
  const [kbStatus, setKbStatus] = useState("");

  const [isMuted, setIsMuted] = useState(false);

  // Caption controls
  const [showCaptions, setShowCaptions] = useState(true);
  const [translateCaptions, setTranslateCaptions] = useState(false);
  const [caption, setCaption] = useState("");

  const wsRef = useRef<WebSocket | null>(null);
  const callActiveRef = useRef(false);
  const mutedRef = useRef(false);
  const endingRef = useRef(false);

  const sentChunkCountRef = useRef(0);
  const receivedChunkCountRef = useRef(0);

  // ============================================================
  // ORB ANIMATIONS
  // ============================================================

  const orbScale = useRef(new Animated.Value(1)).current;
  const orbGlowScale = useRef(new Animated.Value(1)).current;
  const orbGlowOpacity = useRef(new Animated.Value(0.18)).current;
  const orbRingScale = useRef(new Animated.Value(1)).current;
  const orbRingOpacity = useRef(new Animated.Value(0)).current;

  // Main microphone input handler
  const handleNativeInput = useCallback((buffer: NativeInputEvent) => {
    const ws = wsRef.current;

    if (
      mutedRef.current ||
      !callActiveRef.current ||
      ws?.readyState !== WebSocket.OPEN
    ) {
      return;
    }

    try {
      ws.send(
        JSON.stringify({
          type: "audio",
          data: buffer.data,
        })
      );

      sentChunkCountRef.current += 1;

      if (
        sentChunkCountRef.current <= 3 ||
        sentChunkCountRef.current % 50 === 0
      ) {
        console.log("📤 AEC PCM16 16k mono audio sent", {
          chunks: sentChunkCountRef.current,
          bytes: buffer.bytes,
        });
      }
    } catch (error) {
      console.error("❌ Failed to encode/send microphone PCM", error);
    }
  }, []);

  const [isStreaming, setIsStreaming] = useState(false);

  // Native PCM microphone event
  useEffect(() => {
    const subscription = DeviceEventEmitter.addListener(
      "voicePcmInput",
      handleNativeInput
    );

    return () => subscription.remove();
  }, [handleNativeInput]);

  // ============================================================
  // ORB ANIMATION ENGINE
  // ============================================================

  useEffect(() => {
    let animation: Animated.CompositeAnimation | null = null;
    let glowAnimation: Animated.CompositeAnimation | null = null;
    let ringAnimation: Animated.CompositeAnimation | null = null;

    // Speaking = faster and stronger animation
    if (isSpeaking) {
      animation = Animated.loop(
        Animated.sequence([
          Animated.timing(orbScale, {
            toValue: 1.12,
            duration: 380,
            easing: Easing.inOut(Easing.ease),
            useNativeDriver: true,
          }),
          Animated.timing(orbScale, {
            toValue: 1,
            duration: 380,
            easing: Easing.inOut(Easing.ease),
            useNativeDriver: true,
          }),
        ])
      );

      glowAnimation = Animated.loop(
        Animated.sequence([
          Animated.parallel([
            Animated.timing(orbGlowScale, {
              toValue: 1.28,
              duration: 450,
              easing: Easing.out(Easing.ease),
              useNativeDriver: true,
            }),
            Animated.timing(orbGlowOpacity, {
              toValue: 0.42,
              duration: 450,
              useNativeDriver: true,
            }),
          ]),
          Animated.parallel([
            Animated.timing(orbGlowScale, {
              toValue: 1,
              duration: 450,
              easing: Easing.in(Easing.ease),
              useNativeDriver: true,
            }),
            Animated.timing(orbGlowOpacity, {
              toValue: 0.18,
              duration: 450,
              useNativeDriver: true,
            }),
          ]),
        ])
      );

      ringAnimation = Animated.loop(
        Animated.sequence([
          Animated.parallel([
            Animated.timing(orbRingScale, {
              toValue: 1.35,
              duration: 700,
              easing: Easing.out(Easing.ease),
              useNativeDriver: true,
            }),
            Animated.timing(orbRingOpacity, {
              toValue: 0.28,
              duration: 300,
              useNativeDriver: true,
            }),
          ]),
          Animated.timing(orbRingOpacity, {
            toValue: 0,
            duration: 400,
            useNativeDriver: true,
          }),
          Animated.timing(orbRingScale, {
            toValue: 1,
            duration: 1,
            useNativeDriver: true,
          }),
        ])
      );
    }

    // Listening = slower breathing animation
    else if (isListening && isConnected) {
      animation = Animated.loop(
        Animated.sequence([
          Animated.timing(orbScale, {
            toValue: 1.06,
            duration: 1000,
            easing: Easing.inOut(Easing.ease),
            useNativeDriver: true,
          }),
          Animated.timing(orbScale, {
            toValue: 1,
            duration: 1000,
            easing: Easing.inOut(Easing.ease),
            useNativeDriver: true,
          }),
        ])
      );

      glowAnimation = Animated.loop(
        Animated.sequence([
          Animated.timing(orbGlowScale, {
            toValue: 1.12,
            duration: 1000,
            easing: Easing.inOut(Easing.ease),
            useNativeDriver: true,
          }),
          Animated.timing(orbGlowScale, {
            toValue: 1,
            duration: 1000,
            easing: Easing.inOut(Easing.ease),
            useNativeDriver: true,
          }),
        ])
      );
    }

    // Idle
    else {
      Animated.parallel([
        Animated.spring(orbScale, {
          toValue: 1,
          useNativeDriver: true,
        }),
        Animated.spring(orbGlowScale, {
          toValue: 1,
          useNativeDriver: true,
        }),
        Animated.timing(orbGlowOpacity, {
          toValue: 0.16,
          duration: 250,
          useNativeDriver: true,
        }),
        Animated.timing(orbRingOpacity, {
          toValue: 0,
          duration: 200,
          useNativeDriver: true,
        }),
      ]).start();
    }

    animation?.start();
    glowAnimation?.start();
    ringAnimation?.start();

    return () => {
      animation?.stop();
      glowAnimation?.stop();
      ringAnimation?.stop();
    };
  }, [
    isSpeaking,
    isListening,
    isConnected,
    orbScale,
    orbGlowScale,
    orbGlowOpacity,
    orbRingScale,
    orbRingOpacity,
  ]);

  // ============================================================
  // MICROPHONE
  // ============================================================

  const stopMicrophone = useCallback(() => {
    if (isStreaming) {
      pcmAudio?.stopInput();
      console.log("🛑 Microphone streaming stopped");
    }

    setIsStreaming(false);
    setIsListening(false);
  }, [isStreaming]);

  const cleanupCall = useCallback(() => {
    callActiveRef.current = false;

    stopMicrophone();

    pcmAudio?.release();

    setIsConnected(false);
    setIsSpeaking(false);
  }, [stopMicrophone]);

  useEffect(() => {
    return () => {
      callActiveRef.current = false;

      pcmAudio?.stopInput();
      pcmAudio?.release();

      wsRef.current?.close();
    };
  }, []);

  // ============================================================
  // KNOWLEDGE BASE
  // ============================================================

  const checkKnowledgeBaseReady = async () => {
    if (!API_BASE) {
      throw new Error("EXPO_PUBLIC_API_BASE is not configured.");
    }

    const response = await fetch(`${API_BASE}/api/kb/status`);
    const data = await response.json();

    if (!response.ok || !data.exists) {
      throw new Error("SLTMobitel chatbot is not available.");
    }

    setKbStatus("Source: SLTMobitel chatbot knowledge base");

    console.log("✅ SLTMobitel chatbot available", data);
  };

  // ============================================================
  // START MICROPHONE
  // ============================================================

  const startMicrophone = async () => {
    const granted =
      Platform.OS !== "android" ||
      (await PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
        {
          title: "Microphone permission",
          message: "Microphone access is required for the voice agent.",
          buttonPositive: "Allow",
        }
      )) === PermissionsAndroid.RESULTS.GRANTED;

    if (!granted) {
      Alert.alert(
        "Permission Required",
        "Microphone access is required for the voice agent."
      );

      throw new Error("Microphone permission denied.");
    }

    sentChunkCountRef.current = 0;

    pcmAudio?.startInput();

    console.log("🎤 AEC microphone streaming started", {
      sampleRate: INPUT_SAMPLE_RATE,
      channels: 1,
    });

    setIsStreaming(true);
    setIsListening(true);
    setStatus("Listening...");
  };

  // ============================================================
  // SERVER EVENTS
  // ============================================================

  const handleServerEvent = async (message: VoiceEvent) => {
    if (message.type === "ready") {
      console.log("✅ Gemini Live ready");

      setIsConnected(true);
      setStatus("Live call active");

      await startMicrophone();

      return;
    }

    if (message.type === "transcript") {
      setCaption(message.text ?? "");
      return;
    }

    if (message.type === "audio") {
      const sampleRate = message.sampleRate ?? OUTPUT_SAMPLE_RATE;

      if (!message.data) return;

      if (sampleRate !== OUTPUT_SAMPLE_RATE) {
        throw new Error(
          `Unsupported backend audio sample rate: ${sampleRate}`
        );
      }

      if (!pcmAudio) {
        throw new Error(
          "Native PCM player is unavailable. Rebuild the Android development client."
        );
      }

      pcmAudio.enqueue(message.data, sampleRate);

      receivedChunkCountRef.current += 1;

      if (receivedChunkCountRef.current === 1) {
        console.log("🔊 PCM playback started");
      }

      if (
        receivedChunkCountRef.current <= 3 ||
        receivedChunkCountRef.current % 50 === 0
      ) {
        console.log("🔊 PCM audio received", {
          chunks: receivedChunkCountRef.current,
          sampleRate,
        });
      }

      setIsSpeaking(true);
      setStatus("Agent speaking...");

      return;
    }

    if (message.type === "tool") {
      console.log("🔎 Knowledge-base tool call", message.query);
      setStatus(`Searching KB: ${message.query ?? ""}`);
    } else if (message.type === "tool_result") {
      console.log("📚 Knowledge-base tool result");
      setStatus("Knowledge base result received");
    } else if (message.type === "interrupted") {
      console.log("⏸️ Agent interrupted; clearing PCM queue");

      pcmAudio?.clear();

      setIsSpeaking(false);
      setStatus("Interrupted — listening...");
    } else if (message.type === "error") {
      console.error("❌ Backend/Gemini error", message.message);

      setStatus(`Error: ${message.message ?? "Unknown backend error"}`);
    } else {
      console.log("📩 Server event", message);
    }
  };

  // ============================================================
  // START CALL
  // ============================================================

  const startCall = async () => {
    if (callActiveRef.current) return;

    try {
      if (!WS_URL) {
        throw new Error("EXPO_PUBLIC_WS_URL is not configured.");
      }

      if (!pcmAudio) {
        throw new Error(
          "Native PCM player is unavailable. Rebuild the Android development client."
        );
      }

      endingRef.current = false;

      mutedRef.current = false;
      setIsMuted(false);

      setStatus("Checking SLTMobitel chatbot...");
      setKbStatus("");
      setIsSpeaking(false);

      await checkKnowledgeBaseReady();

      setStatus("Connecting...");
      receivedChunkCountRef.current = 0;

      console.log("🔌 Connecting WebSocket", WS_URL);

      // Prevent parallel Live sessions
      callActiveRef.current = true;

      const ws = new WebSocket(WS_URL);

      wsRef.current = ws;

      ws.onopen = () => {
        console.log("✅ WebSocket connected");

        setStatus(
          "WebSocket connected. Waiting for Gemini..."
        );
      };

      ws.onmessage = async (event) => {
        if (typeof event.data !== "string") return;

        try {
          await handleServerEvent(
            JSON.parse(event.data) as VoiceEvent
          );
        } catch (error) {
          console.error(
            "❌ Server event handling failed",
            error
          );

          setStatus(
            error instanceof Error
              ? `Error: ${error.message}`
              : "Server event handling failed"
          );
        }
      };

      ws.onerror = () => {
        console.error("❌ WebSocket error");

        setStatus("WebSocket error");

        cleanupCall();
      };

      ws.onclose = (event) => {
        console.log(
          "🔌 WebSocket closed",
          event.code,
          event.reason
        );

        cleanupCall();

        setStatus(
          endingRef.current
            ? "Idle"
            : `Connection closed (${event.code})`
        );
      };
    } catch (error) {
      console.error("❌ Unable to start voice call", error);

      cleanupCall();

      setStatus(
        error instanceof Error
          ? `Error: ${error.message}`
          : "Unable to start call"
      );
    }
  };

  // ============================================================
  // END CALL
  // ============================================================

  const endCall = () => {
    endingRef.current = true;

    setStatus("Stopping...");

    stopMicrophone();

    const ws = wsRef.current;

    if (ws?.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: "end_audio" }));
      ws.send(JSON.stringify({ type: "stop" }));

      ws.close(1000, "Call ended by user");
    }

    wsRef.current = null;

    cleanupCall();

    setStatus("Idle");

    mutedRef.current = false;

    setIsMuted(false);

    setIsSpeaking(false);

    console.log(
      "✅ Call ended and audio resources released"
    );
  };

  // ============================================================
  // MUTE
  // ============================================================

  const toggleMute = () => {
    const nextMuted = !mutedRef.current;

    mutedRef.current = nextMuted;

    setIsMuted(nextMuted);

    setStatus(
      nextMuted
        ? "Microphone muted"
        : "Listening..."
    );
  };

  // ============================================================
  // UI
  // ============================================================

  const getStatusText = () => {
    if (isMuted) {
      return "Microphone muted";
    }

    if (isSpeaking) {
      return "Speaking...";
    }

    if (isListening && isConnected) {
      return "Listening...";
    }

    if (status === "Idle") {
      return "Tap the button to start";
    }

    return status;
  };

  return (
    <SafeAreaView style={styles.container}>
      {/* ======================================================
          BACKGROUND
      ====================================================== */}

      <View pointerEvents="none" style={styles.background}>
        <View style={styles.backgroundGlowOne} />
        <View style={styles.backgroundGlowTwo} />
      </View>

      {/* ======================================================
          TOP BAR
      ====================================================== */}

      <View style={styles.topBar}>
        <View style={styles.topBarSide}>
          <View style={styles.liveDot} />

          <Text style={styles.connectionText}>
            {isConnected ? "LIVE" : "VOICE"}
          </Text>
        </View>

        <Text style={styles.appTitle}>
          KITO
        </Text>

        <TouchableOpacity
          style={styles.topCloseButton}
          onPress={endCall}
          activeOpacity={0.7}
        >
          <Ionicons
            name="close"
            size={21}
            color="#26374D"
          />
        </TouchableOpacity>
      </View>

      {/* ======================================================
          MAIN VOICE AREA
      ====================================================== */}

      <View style={styles.centerArea}>
        {/* CAPTION */}

        {showCaptions && caption ? (
          <Animated.View
            style={[
              styles.captionBox,
              {
                opacity: isSpeaking ? 1 : 0.92,
              },
            ]}
          >
            <Text style={styles.captionLabel}>
              {translateCaptions
                ? "TRANSLATE"
                : "LIVE CAPTION"}
            </Text>

            <Text style={styles.captionText}>
              {caption}
            </Text>
          </Animated.View>
        ) : null}

        {/* ORB */}

        <View style={styles.orbArea}>
          {/* Outer animated ring */}

          <Animated.View
            pointerEvents="none"
            style={[
              styles.orbRing,
              {
                opacity: orbRingOpacity,
                transform: [
                  {
                    scale: orbRingScale,
                  },
                ],
              },
            ]}
          />

          {/* Glow */}

          <Animated.View
            pointerEvents="none"
            style={[
              styles.orbGlow,
              {
                opacity: orbGlowOpacity,
                transform: [
                  {
                    scale: orbGlowScale,
                  },
                ],
              },
            ]}
          />

          {/* Main orb */}

          <TouchableOpacity
            activeOpacity={0.9}
            onPress={
              isConnected
                ? endCall
                : startCall
            }
            style={styles.orbTouchable}
          >
            <Animated.View
              style={[
                styles.voiceOrb,
                {
                  transform: [
                    {
                      scale: orbScale,
                    },
                  ],
                },
              ]}
            >
              {/* Orb highlights */}

              <View style={styles.orbHighlight} />

              <View style={styles.orbInnerGlow} />

              {/* Icon */}

              <Ionicons
                name={
                  isConnected
                    ? "stop"
                    : "mic"
                }
                size={42}
                color="#FFFFFF"
              />
            </Animated.View>
          </TouchableOpacity>
        </View>

        {/* STATUS */}

        <Text style={styles.mainStatus}>
          {getStatusText()}
        </Text>

        {!isConnected && status === "Idle" ? (
          <Text style={styles.tapHint}>
            Tap the blue circle to speak
          </Text>
        ) : null}

        {kbStatus ? (
          <Text style={styles.kbText}>
            {kbStatus}
          </Text>
        ) : null}
      </View>

      {/* ======================================================
          BOTTOM CONTROLS
      ====================================================== */}

      <View style={styles.bottomSection}>
        <View style={styles.controlsRow}>
          {/* MIC */}

          <TouchableOpacity
            style={[
              styles.controlButton,
              isMuted &&
                styles.controlButtonMuted,
            ]}
            onPress={toggleMute}
            disabled={!isConnected}
            activeOpacity={0.75}
          >
            <Ionicons
              name={
                isMuted
                  ? "mic-off"
                  : "mic-outline"
              }
              size={21}
              color={
                isMuted
                  ? "#E44855"
                  : "#34465D"
              }
            />
          </TouchableOpacity>

          {/* CAPTION */}

          <TouchableOpacity
            style={[
              styles.controlButton,
              showCaptions &&
                styles.controlButtonActive,
            ]}
            onPress={() =>
              setShowCaptions(
                (value) => !value
              )
            }
            activeOpacity={0.75}
          >
            <Ionicons
              name="chatbox-ellipses-outline"
              size={21}
              color={
                showCaptions
                  ? "#2563EB"
                  : "#34465D"
              }
            />

            {showCaptions ? (
              <View
                style={styles.activeIndicator}
              />
            ) : null}
          </TouchableOpacity>

          {/* TRANSLATE */}


          {/* END */}

          <TouchableOpacity
            style={[
              styles.controlButton,
              styles.endControlButton,
            ]}
            onPress={endCall}
            activeOpacity={0.75}
          >
            <Ionicons
              name="close"
              size={22}
              color="#34465D"
            />
          </TouchableOpacity>
        </View>

        <Text style={styles.footerText}>
          Powered by{" "}
          <Text style={styles.footerBold}>
            KITO AI
          </Text>
        </Text>
      </View>
    </SafeAreaView>
  );
}

// ============================================================
// STYLES
// ============================================================

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: "#F4FBFD",
  },

  background: {
    ...StyleSheet.absoluteFillObject,
    overflow: "hidden",
  },

  backgroundGlowOne: {
    position: "absolute",
    width: 430,
    height: 430,
    borderRadius: 215,
    top: -180,
    left: -90,
    backgroundColor: "#D7F7FA",
    opacity: 0.9,
  },

  backgroundGlowTwo: {
    position: "absolute",
    width: 330,
    height: 330,
    borderRadius: 165,
    bottom: -190,
    right: -100,
    backgroundColor: "#E8F4FF",
    opacity: 0.75,
  },

  // ==========================================================
  // TOP
  // ==========================================================

  topBar: {
    height: 64,
    paddingHorizontal: 22,
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
  },

  topBarSide: {
    width: 90,
    flexDirection: "row",
    alignItems: "center",
  },

  liveDot: {
    width: 7,
    height: 7,
    borderRadius: 4,
    backgroundColor: "#3B82F6",
    marginRight: 7,
  },

  connectionText: {
    fontSize: 10,
    fontWeight: "800",
    color: "#7A91A8",
    letterSpacing: 1.2,
  },

  appTitle: {
    fontSize: 17,
    fontWeight: "800",
    color: "#17335D",
    letterSpacing: 2,
  },

  topCloseButton: {
    width: 38,
    height: 38,
    borderRadius: 19,
    backgroundColor: "rgba(255,255,255,0.72)",
    alignItems: "center",
    justifyContent: "center",
    shadowColor: "#52718C",
    shadowOffset: {
      width: 0,
      height: 3,
    },
    shadowOpacity: 0.08,
    shadowRadius: 7,
    elevation: 2,
  },

  // ==========================================================
  // CENTER
  // ==========================================================

  centerArea: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    paddingHorizontal: 22,
  },

  captionBox: {
    width: "88%",
    minHeight: 58,
    borderRadius: 18,
    paddingHorizontal: 18,
    paddingVertical: 11,
    marginBottom: 34,
    backgroundColor: "rgba(255,255,255,0.76)",
    borderWidth: 1,
    borderColor: "rgba(255,255,255,0.95)",

    shadowColor: "#5D8CA0",
    shadowOffset: {
      width: 0,
      height: 5,
    },
    shadowOpacity: 0.08,
    shadowRadius: 12,
    elevation: 3,
  },

  captionLabel: {
    fontSize: 8,
    fontWeight: "800",
    letterSpacing: 1.4,
    color: "#7890A6",
    textAlign: "center",
    marginBottom: 4,
  },

  captionText: {
    fontSize: 14,
    lineHeight: 20,
    fontWeight: "600",
    color: "#203B5D",
    textAlign: "center",
  },

  orbArea: {
    width: 250,
    height: 250,
    alignItems: "center",
    justifyContent: "center",
  },

  orbTouchable: {
    width: 150,
    height: 150,
    alignItems: "center",
    justifyContent: "center",
  },

  // Outer ring
  orbRing: {
    position: "absolute",
    width: 150,
    height: 150,
    borderRadius: 75,
    borderWidth: 2,
    borderColor: "#4D8CFF",
  },

  // Blue glow
  orbGlow: {
    position: "absolute",
    width: 154,
    height: 154,
    borderRadius: 77,
    backgroundColor: "#4B82F1",

    shadowColor: "#2563EB",
    shadowOffset: {
      width: 0,
      height: 0,
    },
    shadowOpacity: 0.6,
    shadowRadius: 32,
    elevation: 12,
  },

  // Main circle
  voiceOrb: {
    width: 116,
    height: 116,
    borderRadius: 58,

    backgroundColor: "#2864E6",

    alignItems: "center",
    justifyContent: "center",

    shadowColor: "#1C55D6",
    shadowOffset: {
      width: 0,
      height: 13,
    },
    shadowOpacity: 0.35,
    shadowRadius: 19,

    elevation: 14,

    overflow: "hidden",
  },

  orbHighlight: {
    position: "absolute",
    width: 86,
    height: 42,
    borderRadius: 50,
    top: -7,
    left: 12,
    backgroundColor: "rgba(255,255,255,0.12)",
    transform: [
      {
        rotate: "-15deg",
      },
    ],
  },

  orbInnerGlow: {
    position: "absolute",
    width: 82,
    height: 82,
    borderRadius: 41,
    backgroundColor: "rgba(255,255,255,0.045)",
  },

  mainStatus: {
    marginTop: 19,
    fontSize: 15,
    fontWeight: "700",
    color: "#536D87",
    textAlign: "center",
  },

  tapHint: {
    marginTop: 7,
    fontSize: 11,
    color: "#8AA0B4",
    textAlign: "center",
  },

  kbText: {
    marginTop: 9,
    fontSize: 9,
    color: "#6B92A0",
    textAlign: "center",
  },

  // ==========================================================
  // BOTTOM
  // ==========================================================

  bottomSection: {
    alignItems: "center",
    paddingHorizontal: 20,
    paddingBottom: 15,
  },

  controlsRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "center",
    gap: 14,
  },

  controlButton: {
    width: 50,
    height: 50,
    borderRadius: 25,

    backgroundColor: "rgba(225,234,243,0.95)",

    alignItems: "center",
    justifyContent: "center",

    shadowColor: "#607C95",
    shadowOffset: {
      width: 0,
      height: 3,
    },
    shadowOpacity: 0.08,
    shadowRadius: 6,

    elevation: 2,
  },

  controlButtonActive: {
    backgroundColor: "#DCEAFF",
  },

  controlButtonMuted: {
    backgroundColor: "#FCE4E6",
  },

  endControlButton: {
    backgroundColor: "#E4EBF2",
  },

  translateIcon: {
    fontSize: 18,
    fontWeight: "800",
    color: "#34465D",
  },

  translateIconActive: {
    color: "#2563EB",
  },

  activeIndicator: {
    position: "absolute",
    bottom: 7,
    width: 4,
    height: 4,
    borderRadius: 2,
    backgroundColor: "#2563EB",
  },

  footerText: {
    marginTop: 12,
    fontSize: 8,
    color: "#8EA0B0",
    textAlign: "center",
  },

  footerBold: {
    fontWeight: "800",
    color: "#6E8296",
  },
});