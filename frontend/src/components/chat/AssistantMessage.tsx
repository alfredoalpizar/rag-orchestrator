import MarkdownPreview from '@uiw/react-markdown-preview';
import type { ChatMessage } from '../../types/api.types';
import { ReasoningTrace } from './ReasoningTrace';
import { ToolCallDisplay } from './ToolCallDisplay';
import { MetricsDisplay } from './MetricsDisplay';
import { IterationBlock } from './IterationBlock';

interface AssistantMessageProps {
  message: ChatMessage;
  currentIteration?: number | null;  // For highlighting active iteration
}

export function AssistantMessage({ message, currentIteration }: AssistantMessageProps) {
  const { content, metadata, timestamp } = message;
  const hasContent = content && content.trim().length > 0;
  const hasIterationData = metadata?.iterationData && metadata.iterationData.length > 0;

  // Use iteration-based display when we have iteration data
  const useIterationDisplay = hasIterationData;

  return (
    <div className="max-w-[85%]">
      <div className="bg-white border border-gray-200 rounded-lg shadow-sm overflow-hidden">
        <div className="p-4">
          {/* Iteration Blocks - show when we have iteration data */}
          {useIterationDisplay && (
            <div className="space-y-3 mb-4">
              <div className="text-xs font-medium text-gray-500 uppercase tracking-wider">
                Agentic Loop ({metadata.iterationData!.length} iteration{metadata.iterationData!.length > 1 ? 's' : ''})
              </div>
              {metadata.iterationData!.map((iterData) => (
                <IterationBlock
                  key={iterData.iteration}
                  iterationData={iterData}
                  isActive={currentIteration === iterData.iteration}
                />
              ))}
            </div>
          )}

          {/* Final Answer Section */}
          {hasContent && (
            <div className={useIterationDisplay ? 'border-t border-gray-200 pt-4' : ''}>
              {useIterationDisplay && (
                <div className="text-xs font-medium text-green-600 uppercase tracking-wider mb-2 flex items-center gap-1">
                  <span>✓</span> Final Answer
                </div>
              )}
              <MarkdownPreview
                source={content}
                style={{
                  backgroundColor: 'transparent',
                  color: '#374151',
                  fontSize: '0.875rem',
                }}
              />
            </div>
          )}

          {/* Loading state when no content yet */}
          {!hasContent && !useIterationDisplay && (
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

          {/* Legacy reasoning trace - only show if NOT using iteration display */}
          {!useIterationDisplay && (metadata?.reasoningContent || (metadata?.reasoning && metadata.reasoning.length > 0)) && (
            <ReasoningTrace
              reasoning={metadata?.reasoning}
              reasoningContent={metadata?.reasoningContent}
            />
          )}

          {/* Legacy tool calls - only show if NOT using iteration display */}
          {!useIterationDisplay && metadata?.toolCalls && metadata.toolCalls.length > 0 && (
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
