import { useState } from 'react';
import type { ToolCall } from '../../types/api.types';

interface ToolCallDisplayProps {
  toolCall: ToolCall;
}

export function ToolCallDisplay({ toolCall }: ToolCallDisplayProps) {
  const [expanded, setExpanded] = useState(false);

  const getStatusColor = () => {
    if (toolCall.success) return 'bg-green-50 border-green-400';
    if (toolCall.error) return 'bg-red-50 border-red-400';
    return 'bg-yellow-50 border-yellow-400';
  };

  const getStatusIcon = () => {
    if (toolCall.success) return '✓';
    if (toolCall.error) return '✗';
    return '⏳';
  };

  return (
    <div className={`mt-3 rounded-lg border-l-4 ${getStatusColor()}`}>
      <div className="px-3 py-2">
        <div className="flex items-center justify-between">
          <span className="text-sm font-medium flex items-center gap-2">
            <span>🔧</span>
            <span>{toolCall.name}</span>
            <span className="text-xs">{getStatusIcon()}</span>
          </span>
          <button
            onClick={() => setExpanded(!expanded)}
            className="text-sm text-gray-600 hover:text-gray-900"
          >
            {expanded ? '▲' : '▼'}
          </button>
        </div>

        {expanded && (
          <div className="mt-2 space-y-2">
            <div className="text-xs">
              <strong className="text-gray-700">Arguments:</strong>
              <pre className="bg-gray-800 text-gray-100 p-2 rounded mt-1 overflow-x-auto text-xs">
                {JSON.stringify(toolCall.arguments, null, 2)}
              </pre>
            </div>

            {toolCall.result !== undefined && (
              <div className="text-xs">
                <strong className="text-gray-700">Result:</strong>
                <pre className="bg-gray-800 text-gray-100 p-2 rounded mt-1 overflow-x-auto text-xs">
                  {JSON.stringify(toolCall.result, null, 2)}
                </pre>
              </div>
            )}

            {toolCall.error && (
              <div className="text-xs">
                <strong className="text-red-700">Error:</strong>
                <p className="text-red-600 mt-1">{toolCall.error}</p>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
