import { useState } from 'react';

interface ReasoningTraceProps {
  reasoning?: string[];
  reasoningContent?: string;  // Streaming content as a single string
}

export function ReasoningTrace({ reasoning, reasoningContent }: ReasoningTraceProps) {
  const [collapsed, setCollapsed] = useState(false);

  // Combine array-based reasoning with streaming content
  const displayContent = reasoningContent || (reasoning && reasoning.length > 0 ? reasoning.join('\n\n') : '');

  if (!displayContent) {
    return null;
  }

  return (
    <div className="mt-3 border-l-4 border-purple-400 bg-purple-50 rounded-r-lg overflow-hidden">
      <button
        onClick={() => setCollapsed(!collapsed)}
        className="w-full text-left px-3 py-2 flex items-center justify-between hover:bg-purple-100 transition-colors"
      >
        <span className="text-sm font-medium text-purple-900">🧠 Reasoning</span>
        <span className="text-purple-700">{collapsed ? '▼' : '▲'}</span>
      </button>
      {!collapsed && (
        <div className="px-3 py-2 text-sm text-gray-700 italic whitespace-pre-wrap border-t border-purple-200">
          {displayContent}
        </div>
      )}
    </div>
  );
}
