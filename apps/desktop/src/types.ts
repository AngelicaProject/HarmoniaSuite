export type GatewayProfile =
  | {
      id: string;
      mode: "local";
    }
  | {
      id: string;
      mode: "remote";
      url: string;
      credentialRef?: string;
    };

export type DesktopConfig = {
  profiles: GatewayProfile[];
  activeGatewayId: string;
  allowInsecureRemote: boolean;
};

export type ActiveGateway = {
  profile: GatewayProfile;
  baseUrl: string;
};

export const DEFAULT_GATEWAY_PROFILE: GatewayProfile = {
  id: "local",
  mode: "local",
};
