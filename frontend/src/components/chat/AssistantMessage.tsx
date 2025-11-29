import MarkdownPreview from '@uiw/react-markdown-preview';
import type { ChatMessage } from '../../types/api.types';
import { ReasoningTrace } from './ReasoningTrace';
import { ToolCallDisplay } from './ToolCallDisplay';
import { MetricsDisplay } from './MetricsDisplay';

interface AssistantMessageProps {
  message: ChatMessage;
}

export function AssistantMessage({ message }: AssistantMessageProps) {
  const { content, metadata, timestamp } = message;
  const hasContent = content && content.trim().length > 0;

  return (
    <div className="max-w-[85%]">
      <div className="bg-white border border-gray-200 rounded-lg shadow-sm overflow-hidden">
        {/* Main content */}
        <div className="p-4">
          {hasContent ? (
            <MarkdownPreview
              source={content}
              style={{
                backgroundColor: 'transparent',
                color: '#374151',
                fontSize: '0.875rem',
              }}
            />
          ) : (
            <div className="flex items-center gap-2 text-gray-500">
              <div className="animate-spin h-4 w-4 border-2 border-gray-400 border-t-transparent rounded-full" />
              <span className="text-sm">Generating response...</span>
            </div>
          )}

          {/* Execution plan */}
          {metadata?.executionPlan && (
            <div className="mt-3 border-l-4 border-blue-400 bg-blue-50 rounded-r-lg px-3 py-2">
              <div className="text-sm font-medium text-blue-900 mb-1">📋 Execution Plan</div>
              <div className="text-sm text-gray-700 whitespace-pre-wrap">
                {metadata.executionPlan}
              </div>
            </div>
          )}

          {/* Reasoning trace */}
          {metadata?.reasoning && metadata.reasoning.length > 0 && (
            <ReasoningTrace reasoning={metadata.reasoning} />
          )}

          {/* Tool calls */}
          {metadata?.toolCalls && metadata.toolCalls.length > 0 && (
            <div className="mt-3 space-y-2">
              {metadata.toolCalls.map((toolCall) => (
                <ToolCallDisplay key={toolCall.id} toolCall={toolCall} />
              ))}
            </div>
          )}
        </div>

        {/* Metrics */}
        {metadata?.metrics && <MetricsDisplay metrics={metadata.metrics} />}
      </div>

      {/* Timestamp */}
      <div className="text-xs text-gray-500 mt-1">
        {new Date(timestamp).toLocaleTimeString()}
      </div>
    </div>
  );
}
