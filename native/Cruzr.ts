import { NativeModules, Platform } from "react-native";

type CruzrBridge = {
  startSpeakingGesture(): void;
  stopSpeakingGesture(): void;
  performMotion(name: string): void;
};

const cruzr = NativeModules.Cruzr as CruzrBridge | undefined;

// These calls are intentionally no-ops outside an Android Cruzr build. Robot
// motion is optional enhancement; it must never disrupt the voice session.
export function startSpeakingGesture(): void {
  if (Platform.OS === "android") cruzr?.startSpeakingGesture();
}

export function stopSpeakingGesture(): void {
  if (Platform.OS === "android") cruzr?.stopSpeakingGesture();
}

/** One-shot documented preset, useful for on-robot validation (for example, nod). */
export function performMotion(name: string): void {
  if (Platform.OS === "android") cruzr?.performMotion(name);
}
