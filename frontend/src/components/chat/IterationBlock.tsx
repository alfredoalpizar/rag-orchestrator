import { useState } from 'react';
import type { IterationData } from '../../types/api.types';
import { ToolCallDisplay } from './ToolCallDisplay';

interface IterationBlockProps {
  iterationData: IterationData;
  isActive?: boolean;  // Is this the currently streaming iteration?
}

export function IterationBlock({ iterationData, isActive = false }: IterationBlockProps) {
  const [isThinkingExpanded, setIsThinkingExpanded] = useState(false);
  const { iteration, reasoning, toolCalls, intermediateContent } = iterationData;

  const hasReasoning = reasoning && reasoning.trim().length > 0;
  const hasToolCalls = toolCalls && toolCalls.length > 0;
  const hasIntermediateContent = intermediateContent && intermediateContent.trim().length > 0;

  // Don't render empty iteration blocks
  if (!hasReasoning && !hasToolCalls && !hasIntermediateContent) {
    return null;
  }

  return (
    <div className={`border rounded-lg overflow-hidden ${isActive ? 'border-blue-400 bg-blue-50/30' : 'border-gray-200 bg-gray-50/50'}`}>
      {/* Iteration Header */}
      <div className={`px-3 py-2 flex items-center justify-between ${isActive ? 'bg-blue-100/50' : 'bg-gray-100/50'}`}>
        <div className="flex items-center gap-2">
          <span className={`text-xs font-semibold px-2 py-0.5 rounded ${isActive ? 'bg-blue-500 text-white' : 'bg-gray-500 text-white'}`}>
            Iteration {iteration}
          </span>
          {isActive && (
            <span className="flex items-center gap-1 text-xs text-blue-600">
              <span className="animate-pulse h-2 w-2 bg-blue-500 rounded-full"></span>
              Processing...
            </span>
          )}
        </div>
        {hasToolCalls && (
          <span className="text-xs text-gray-500">
            {toolCalls.length} tool call{toolCalls.length > 1 ? 's' : ''}
          </span>
        )}
      </div>

      <div className="p-3 space-y-3">
        {/* Thinking/Reasoning Section - Collapsible */}
        {hasReasoning && (
          <div className="border border-purple-200 rounded-lg overflow-hidden">
            <button
              onClick={() => setIsThinkingExpanded(!isThinkingExpanded)}
              className="w-full px-3 py-2 bg-purple-50 flex items-center justify-between hover:bg-purple-100 transition-colors"
            >
              <div className="flex items-center gap-2">
                <span className="text-purple-600">🧠</span>
                <span className="text-sm font-medium text-purple-900">Thinking</span>
                <span className="text-xs text-purple-600">
                  ({reasoning.length} chars)
                </span>
              </div>
              <svg
                className={`w-4 h-4 text-purple-600 transition-transform ${isThinkingExpanded ? 'rotate-180' : ''}`}
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
              >
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
              </svg>
            </button>
            {isThinkingExpanded && (
              <div className="px-3 py-2 bg-white max-h-64 overflow-y-auto">
                <pre className="text-xs text-gray-700 whitespace-pre-wrap font-mono">
                  {reasoning}
                </pre>
              </div>
            )}
          </div>
        )}

        {/* Tool Calls Section */}
        {hasToolCalls && (
          <div className="space-y-2">
            {toolCalls.map((toolCall) => (
              <ToolCallDisplay key={toolCall.id} toolCall={toolCall} />
            ))}
          </div>
        )}

        {/* Intermediate Content (if any non-final content was emitted) */}
        {hasIntermediateContent && (
          <div className="border border-gray-200 rounded-lg px-3 py-2 bg-white">
            <div className="text-xs text-gray-500 mb-1">Intermediate Output:</div>
            <div className="text-sm text-gray-700 whitespace-pre-wrap">
              {intermediateContent}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
