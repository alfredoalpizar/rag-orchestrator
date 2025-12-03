import type { CompletedEvent } from '../../types/api.types';

interface MetricsDisplayProps {
  metrics: CompletedEvent['metrics'];
}

export function MetricsDisplay({ metrics }: MetricsDisplayProps) {
  if (!metrics) return null;

  const { totalTokens, iterations, processingTimeMs } = metrics;

  return (
    <div className="bg-gray-100 border-t border-gray-300 px-3 py-2 rounded-b-lg">
      <div className="text-xs text-gray-600 flex items-center gap-4 flex-wrap">
        {totalTokens !== undefined && (
          <span className="flex items-center gap-1">
            <span>📊</span>
            <span>{totalTokens.toLocaleString()} tokens</span>
          </span>
        )}
        {iterations !== undefined && (
          <span>• {iterations} iterations</span>
        )}
        {processingTimeMs !== undefined && (
          <span>• {(processingTimeMs / 1000).toFixed(1)}s</span>
        )}
      </div>
    </div>
  );
}
