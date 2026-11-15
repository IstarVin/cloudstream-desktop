// API shapes. Those the web UI already declares come from web/src/lib/types.ts; the rest mirror shared/.../api/Dto.kt.
export type { DeviceInfo, DeviceKind, PlayTitleRequest, Provider } from "$shared/types";

export interface AuthStatus {
  authed: boolean;
  loopback: boolean;
  configured: boolean;
  workerUrl: string;
  serverName: string;
}

export interface DeviceAuthorization {
  userCode: string;
  deviceCode: string;
  verificationUri: string;
  expiresIn: number;
  interval: number;
}

export interface AuthResult {
  status: "pending" | "approved";
  token?: string;
  expiresAt?: number;
}
