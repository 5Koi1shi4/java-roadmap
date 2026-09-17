import { createContext, useContext, useMemo, useState, type ReactNode } from 'react';

export type AuthState = {
  accessToken: string | null;
  login: (accessToken: string) => void;
  logout: () => void;
  authorization: () => string | null;
};

export function createAuthState(): AuthState {
  let accessToken: string | null = null;
  return {
    get accessToken() {
      return accessToken;
    },
    login(token: string) {
      accessToken = token;
    },
    logout() {
      accessToken = null;
    },
    authorization() {
      return accessToken ? `Bearer ${accessToken}` : null;
    }
  };
}

type AuthContextValue = AuthState;

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [accessToken, setAccessToken] = useState<string | null>(null);
  const value = useMemo<AuthContextValue>(() => ({
    accessToken,
    login: (token: string) => setAccessToken(token),
    logout: () => setAccessToken(null),
    authorization: () => (accessToken ? `Bearer ${accessToken}` : null)
  }), [accessToken]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const auth = useContext(AuthContext);
  if (!auth) {
    throw new Error('useAuth 必须在 AuthProvider 内使用');
  }
  return auth;
}
