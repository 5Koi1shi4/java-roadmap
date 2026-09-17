import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { AuthProvider } from './auth/AuthProvider';
import { SupportDesk } from './support/SupportDesk';
import './styles/tokens.css';
import './styles/layout.css';

const root = document.getElementById('root');
if (!root) {
  throw new Error('应用根节点不存在');
}

createRoot(root).render(
  <StrictMode>
    <AuthProvider>
      <SupportDesk />
    </AuthProvider>
  </StrictMode>
);
