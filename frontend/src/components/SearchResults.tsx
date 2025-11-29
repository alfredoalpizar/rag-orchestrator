import type { SearchResult } from '../types/api.types';

interface SearchResultsProps {
  results: SearchResult[];
  query: string;
  loading: boolean;
  error: string | null;
  onViewDocument: (docId: string) => void;
}

export default function SearchResults({
  results,
  query,
  loading,
  error,
  onViewDocument
}: SearchResultsProps) {

  if (loading) {
    return (
      <div className="flex justify-center items-center h-64">
        <div className="text-gray-500">Searching...</div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="bg-red-50 border border-red-200 text-red-800 px-4 py-3 rounded-md">
        {error}
      </div>
    );
  }

  if (results.length === 0) {
    return (
      <div className="text-center py-12 bg-gray-50 rounded-lg">
        <p className="text-gray-500">No results found for "{query}"</p>
        <p className="text-sm text-gray-400 mt-2">Try different keywords or check your spelling</p>
      </div>
    );
  }

  const formatScore = (score: number) => {
    return Math.round(score * 100);
  };

  const getChunkTypeDisplay = (chunkType: string) => {
    const typeMap: Record<string, string> = {
      'workflow_step': 'Workflow Step',
      'workflow_overview': 'Workflow Overview',
      'faq_qa': 'FAQ Q&A',
      'reference_section': 'Reference',
      'troubleshooting_issue': 'Troubleshooting',
      'semantic_chunk': 'Content'
    };
    return typeMap[chunkType] || chunkType;
  };

  return (
    <div className="space-y-4">
      <div className="text-sm text-gray-600 mb-4">
        Found {results.length} results for "{query}"
      </div>

      {results.map((result) => (
        <div
          key={result.chunk_id}
          className="bg-white border border-gray-200 rounded-lg p-6 hover:shadow-md transition-shadow cursor-pointer"
          onClick={() => onViewDocument(result.document.doc_id)}
        >
          {/* Document header */}
          <div className="flex items-start justify-between mb-3">
            <div className="flex-1">
              <h3 className="text-lg font-semibold text-gray-900 hover:text-blue-600">
                {result.document.title}
              </h3>
              <div className="flex items-center gap-3 mt-1">
                <span className={`px-2 py-1 text-xs font-medium rounded-full ${
                  result.document.category === 'workflow' ? 'bg-blue-100 text-blue-800' :
                  result.document.category === 'faq' ? 'bg-green-100 text-green-800' :
                  result.document.category === 'reference' ? 'bg-purple-100 text-purple-800' :
                  result.document.category === 'troubleshooting' ? 'bg-orange-100 text-orange-800' :
                  'bg-gray-100 text-gray-800'
                }`}>
                  {result.document.category}
                </span>
                <span className="text-xs text-gray-500">
                  {getChunkTypeDisplay(result.chunk.chunk_type)}
                </span>
                <span className="text-xs text-gray-500">
                  {formatScore(result.score)}% match
                </span>
              </div>
            </div>
          </div>

          {/* Chunk context */}
          {(result.chunk.chunk_heading || result.chunk.question) && (
            <div className="mb-3">
              {result.chunk.chunk_heading && (
                <h4 className="text-sm font-medium text-gray-700 mb-1">
                  {result.chunk.step_number && `Step ${result.chunk.step_number}: `}
                  {result.chunk.chunk_heading}
                </h4>
              )}
              {result.chunk.question && (
                <h4 className="text-sm font-medium text-gray-700 mb-1">
                  Q: {result.chunk.question}
                </h4>
              )}
            </div>
          )}

          {/* Chunk content */}
          <div className="text-gray-700 text-sm leading-relaxed">
            <div className="bg-gray-50 p-3 rounded border-l-4 border-blue-200">
              {result.chunk_text.length > 200
                ? `${result.chunk_text.substring(0, 200)}...`
                : result.chunk_text
              }
            </div>
          </div>

          {/* Tags */}
          {result.document.tags.length > 0 && (
            <div className="mt-3 flex flex-wrap gap-1">
              {result.document.tags.slice(0, 3).map(tag => (
                <span key={tag} className="px-2 py-1 bg-gray-100 text-gray-600 text-xs rounded">
                  {tag}
                </span>
              ))}
              {result.document.tags.length > 3 && (
                <span className="px-2 py-1 bg-gray-100 text-gray-600 text-xs rounded">
                  +{result.document.tags.length - 3} more
                </span>
              )}
            </div>
          )}

          {/* Click indicator */}
          <span className="mt-3 text-xs text-blue-600 hover:text-blue-800 hover:underline cursor-pointer inline-block">
            Click to view full document →
          </span>
        </div>
      ))}
    </div>
  );
}