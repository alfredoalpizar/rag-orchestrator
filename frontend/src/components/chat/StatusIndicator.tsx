
interface StatusIndicatorProps {
  status: string;
}

export function StatusIndicator({ status }: StatusIndicatorProps) {
  return (
    <div className="bg-blue-50 border-t border-blue-200 px-4 py-2 flex items-center gap-2">
      <div className="animate-spin h-4 w-4 border-2 border-blue-600 border-t-transparent rounded-full" />
      <span className="text-sm text-blue-900">{status}</span>
    </div>
  );
}
