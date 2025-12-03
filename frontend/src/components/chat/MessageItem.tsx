import type { ChatMessage } from '../../types/api.types';

interface MessageItemProps {
  message: ChatMessage;
}

export function MessageItem({ message }: MessageItemProps) {
  return (
    <div className="ml-auto max-w-[70%]">
      <div className="bg-blue-600 text-white rounded-2xl px-4 py-2 shadow-sm">
        <p className="whitespace-pre-wrap break-words">{message.content}</p>
      </div>
      <div className="text-xs text-gray-500 mt-1 text-right">
        {new Date(message.timestamp).toLocaleTimeString()}
      </div>
    </div>
  );
}
