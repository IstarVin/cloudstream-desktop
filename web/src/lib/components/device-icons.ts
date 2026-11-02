import { AppWindow, Monitor, MonitorPlay, Smartphone, Tv } from "@lucide/svelte";
import type { DeviceKind } from "../types";

/** One icon per kind of player, for the play-on chips and the remote. */
export const DEVICE_ICONS: Record<DeviceKind | "browser", typeof Monitor> = {
  browser: AppWindow,
  web: Monitor,
  phone: Smartphone,
  tv: Tv,
  mpv: MonitorPlay,
};
