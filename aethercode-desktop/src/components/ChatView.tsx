// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

import { MessageList } from './MessageList';
import { MessageInput } from './MessageInput';
import './ChatView.css';

export function ChatView() {
  return (
    <div className="chat-view">
      <MessageList />
      <MessageInput />
    </div>
  );
}
