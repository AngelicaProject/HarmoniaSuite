export interface CredentialStore {
  authorizationHeader(credentialRef?: string): Promise<string | undefined>;
}

/**
 * Phase 1 deliberately has no plaintext fallback. Native Credential Manager/
 * Secret Service adapters are introduced with the installer/security phases.
 */
export class OsCredentialStore implements CredentialStore {
  async authorizationHeader(_credentialRef?: string): Promise<string | undefined> {
    return undefined;
  }
}
