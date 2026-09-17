export type CaseType = 'DISPUTE' | 'WARRANTY';

export type AnswerRequest = {
  question: string;
  orderId?: string;
  caseId?: string;
  caseType?: CaseType;
};

export type SourceView = {
  sourceId: string;
  title: string;
  version: string;
};

export type StatusView = {
  id: string;
  type: string;
  status: string;
  createdAt: string;
  deadline?: string | null;
};

export type AnswerResponse = {
  answer: string;
  sources: SourceView[];
  status?: StatusView | null;
};

export type ResourceType = 'orders' | 'disputes' | 'warranties';

export type ResourcePage = {
  items: StatusView[];
  nextCursor?: string | null;
};

export type VerificationRequest = {
  email: string;
  purpose: 'REGISTER';
};

export type RegisterRequest = {
  email: string;
  password: string;
  code: string;
};

export type LoginRequest = {
  email: string;
  password: string;
};

export type LoginResponse = {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  userId: string;
  roles: string[];
};

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly userMessage: string;
  readonly retryable: boolean;

  constructor(status: number, code: string, userMessage: string, retryable = false) {
    super(userMessage);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.userMessage = userMessage;
    this.retryable = retryable;
  }
}

export function mapStatus(status: number): ApiError {
  switch (status) {
    case 401:
    case 403:
      return new ApiError(status, status === 401 ? 'UNAUTHENTICATED' : 'FORBIDDEN', '无权查看');
    case 404:
      return new ApiError(status, 'RESOURCE_NOT_FOUND', '资源不存在');
    case 429:
      return new ApiError(status, 'RATE_LIMITED', '请求太频繁', true);
    case 400:
      return new ApiError(status, 'INVALID_REQUEST', '请求参数有误');
    default:
      return new ApiError(status, 'DEPENDENCY_UNAVAILABLE', '服务暂不可用', true);
  }
}

async function readJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    throw mapStatus(response.status);
  }
  return response.json() as Promise<T>;
}

export async function askSupport(
  request: AnswerRequest,
  token: string | null
): Promise<AnswerResponse> {
  const response = await fetch('/api/ai/support/answers', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify(request),
    cache: 'no-store'
  });
  return readJson<AnswerResponse>(response);
}

export async function listResources(
  type: ResourceType,
  token: string
): Promise<ResourcePage> {
  const response = await fetch(`/api/support/${type}`, {
    method: 'GET',
    headers: { Authorization: `Bearer ${token}` },
    cache: 'no-store'
  });
  return readJson<ResourcePage>(response);
}

export async function issueVerification(request: VerificationRequest): Promise<void> {
  const response = await fetch('/api/auth/email-verifications', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
    cache: 'no-store'
  });
  await readJson<unknown>(response);
}

export async function registerAccount(request: RegisterRequest): Promise<void> {
  const response = await fetch('/api/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
    cache: 'no-store'
  });
  await readJson<unknown>(response);
}

export async function loginAccount(request: LoginRequest): Promise<LoginResponse> {
  const response = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
    cache: 'no-store'
  });
  return readJson<LoginResponse>(response);
}
