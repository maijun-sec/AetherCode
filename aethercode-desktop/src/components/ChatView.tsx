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
