import { Link } from 'react-router-dom';
import type { DocumentRef } from '../types/api.types';

interface DocumentTableProps {
  documents: DocumentRef[];
  onView: (docId: string) => void;
  onEdit: (docId: string) => void;
  onDelete: (docId: string) => void;
}

export default function DocumentTable({ documents, onView, onEdit, onDelete }: DocumentTableProps) {

  const getCategoryBadgeColor = (category: string) => {
    const colors: Record<string, string> = {
      workflow: 'bg-blue-100 text-blue-800',
      faq: 'bg-green-100 text-green-800',
      reference: 'bg-purple-100 text-purple-800',
      troubleshooting: 'bg-orange-100 text-orange-800',
    };
    return colors[category] || 'bg-gray-100 text-gray-800';
  };

  const formatDate = (dateString: string) => {
    const date = new Date(dateString);
    return date.toLocaleDateString('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric'
    });
  };

  const formatFileSize = (bytes: number) => {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  };

  if (documents.length === 0) {
    return (
      <div className="text-center py-12 bg-gray-50 rounded-lg">
        <p className="text-gray-500">No documents found</p>
        <p className="text-sm text-gray-400 mt-2">Create your first document to get started</p>
      </div>
    );
  }

  return (
    <div className="overflow-x-auto">
      <table className="min-w-full bg-white border border-gray-200 rounded-lg">
        <thead className="bg-gray-50">
          <tr>
            <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
              Document ID
            </th>
            <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
              Category
            </th>
            <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
              Size
            </th>
            <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
              Last Modified
            </th>
            <th className="px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">
              Actions
            </th>
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-200">
          {documents.map((doc) => (
            <tr key={doc.id} className="hover:bg-gray-50">
              <td className="px-6 py-4 whitespace-nowrap">
                <div className="text-sm font-medium text-gray-900">{doc.id}</div>
                <div className="text-xs text-gray-500">{doc.path}</div>
              </td>
              <td className="px-6 py-4 whitespace-nowrap">
                <span className={`px-2 py-1 inline-flex text-xs leading-5 font-semibold rounded-full ${getCategoryBadgeColor(doc.category)}`}>
                  {doc.category}
                </span>
              </td>
              <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                {formatFileSize(doc.sizeBytes)}
              </td>
              <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                {formatDate(doc.lastModified)}
              </td>
              <td className="px-6 py-4 whitespace-nowrap text-right text-sm font-medium">
                <Link
                  to={`/documents/${doc.id}`}
                  className="text-blue-600 hover:text-blue-900 hover:underline mr-3 cursor-pointer transition-colors"
                >
                  View
                </Link>
                <Link
                  to={`/documents/${doc.id}/edit`}
                  className="text-indigo-600 hover:text-indigo-900 hover:underline mr-3 cursor-pointer transition-colors"
                >
                  Edit
                </Link>
                <button
                  onClick={() => onDelete(doc.id)}
                  className="text-red-600 hover:text-red-900 hover:underline cursor-pointer transition-colors"
                >
                  Delete
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}