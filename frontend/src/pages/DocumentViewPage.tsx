import { useState, useEffect } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { useDocuments } from '../hooks/useDocuments';
import type { DocumentResponse } from '../types/api.types';
import MarkdownPreview from '@uiw/react-markdown-preview';

export default function DocumentViewPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { getDocument, deleteDocument } = useDocuments();
  const [document, setDocument] = useState<DocumentResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [deleteConfirm, setDeleteConfirm] = useState(false);

  useEffect(() => {
    if (id) {
      loadDocument(id);
    }
  }, [id]);

  const loadDocument = async (docId: string) => {
    try {
      setLoading(true);
      setError(null);
      const doc = await getDocument(docId);
      console.log('Loaded document:', doc);
      setDocument(doc);
    } catch (err) {
      console.error('Failed to load document:', err);
      setError('Failed to load document');
    } finally {
      setLoading(false);
    }
  };

  const handleDelete = async () => {
    if (!id) return;

    try {
      await deleteDocument(id);
      alert('Document deleted successfully');
      navigate('/documents');
    } catch (err) {
      console.error('Failed to delete document:', err);
      alert('Failed to delete document');
    }
  };

  if (loading) {
    return (
      <div className="flex justify-center items-center h-64">
        <div className="text-gray-600 text-lg">Loading document...</div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="p-6 max-w-2xl mx-auto">
        <div className="bg-red-50 border-l-4 border-red-500 text-red-800 px-6 py-4 rounded-lg shadow-md">
          <p className="font-semibold">Error</p>
          <p>{error}</p>
        </div>
        <Link
          to="/documents"
          className="mt-6 inline-block px-5 py-2.5 bg-blue-600 text-white font-medium rounded-lg hover:bg-blue-700 transition-colors shadow-md"
        >
          Back to Documents
        </Link>
      </div>
    );
  }

  if (!document) {
    return (
      <div className="p-6 max-w-2xl mx-auto">
        <div className="text-gray-600 text-lg mb-6">Document not found</div>
        <Link
          to="/documents"
          className="inline-block px-5 py-2.5 bg-blue-600 text-white font-medium rounded-lg hover:bg-blue-700 transition-colors shadow-md"
        >
          Back to Documents
        </Link>
      </div>
    );
  }

  return (
    <div className="max-w-4xl mx-auto p-6">
      <div className="flex justify-between items-start mb-8">
        <h1 className="text-3xl font-bold text-gray-900">{document?.metadata?.title || 'Unknown Document'}</h1>
        <Link
          to="/documents"
          className="px-4 py-2 bg-white border-2 border-gray-300 text-gray-800 font-medium rounded-lg hover:bg-gray-50 hover:border-gray-400 transition-colors shadow-sm"
        >
          Back to List
        </Link>
      </div>

      <div className="bg-white p-6 rounded-xl shadow-md border border-gray-200 mb-6">
        <h3 className="text-xl font-semibold text-gray-800 mb-4">Metadata</h3>
        <div className="grid grid-cols-2 gap-4">
          <div>
            <span className="font-semibold text-gray-700">ID:</span>{' '}
            <span className="text-gray-900">{document?.metadata?.id || 'N/A'}</span>
          </div>
          <div>
            <span className="font-semibold text-gray-700">Category:</span>{' '}
            <span className="px-3 py-1 bg-blue-100 text-blue-900 rounded-full font-medium">
              {document?.metadata?.category || 'N/A'}
            </span>
          </div>
          <div>
            <span className="font-semibold text-gray-700">Status:</span>{' '}
            <span className={`px-3 py-1 rounded-full font-medium ${
              document?.metadata?.status === 'published' ? 'bg-green-100 text-green-900' :
              document?.metadata?.status === 'draft' ? 'bg-yellow-100 text-yellow-900' :
              'bg-gray-100 text-gray-900'
            }`}>
              {document?.metadata?.status || 'N/A'}
            </span>
          </div>
          {document?.metadata?.tags && document.metadata.tags.length > 0 && (
            <div>
              <span className="font-semibold text-gray-700">Tags:</span>{' '}
              {document.metadata.tags.map(tag => (
                <span key={tag} className="inline-block px-3 py-1 mr-2 bg-purple-100 text-purple-900 rounded-full text-sm font-medium">
                  {tag}
                </span>
              ))}
            </div>
          )}
        </div>
      </div>

      <div className="bg-white p-6 rounded-xl shadow-md border border-gray-200">
        <h3 className="text-xl font-semibold text-gray-800 mb-4">Content</h3>
        {document?.content?.summary && (
          <div className="mb-6">
            <h4 className="font-semibold text-gray-800 mb-2">Summary:</h4>
            <p className="text-gray-700 leading-relaxed">{document.content.summary}</p>
          </div>
        )}
        <div>
          <h4 className="font-semibold text-gray-800 mb-3">Body:</h4>
          <div className="prose prose-slate max-w-none bg-white p-6 rounded-lg border border-gray-200 shadow-sm">
            <MarkdownPreview
              source={document?.content?.body || 'No content available'}
              style={{
                backgroundColor: 'transparent',
                color: '#1f2937',
                fontSize: '0.95rem',
                lineHeight: '1.7'
              }}
            />
          </div>
        </div>
      </div>

      <div className="mt-8 flex flex-wrap gap-3">
        <Link
          to={`/documents/${id}/edit`}
          className="px-5 py-2.5 bg-blue-600 text-white font-medium rounded-lg hover:bg-blue-700 transition-colors shadow-md hover:shadow-lg"
        >
          Edit Document
        </Link>

        {!deleteConfirm ? (
          <button
            onClick={() => setDeleteConfirm(true)}
            className="px-5 py-2.5 bg-red-600 text-white font-medium rounded-lg hover:bg-red-700 transition-colors shadow-md hover:shadow-lg"
          >
            Delete Document
          </button>
        ) : (
          <div className="flex gap-3">
            <button
              onClick={handleDelete}
              className="px-5 py-2.5 bg-red-600 text-white font-medium rounded-lg hover:bg-red-700 transition-colors shadow-md hover:shadow-lg"
            >
              Confirm Delete
            </button>
            <button
              onClick={() => setDeleteConfirm(false)}
              className="px-5 py-2.5 bg-white border-2 border-gray-300 text-gray-800 font-medium rounded-lg hover:bg-gray-50 hover:border-gray-400 transition-colors shadow-sm"
            >
              Cancel
            </button>
          </div>
        )}

        <Link
          to="/documents"
          className="px-5 py-2.5 bg-white border-2 border-gray-300 text-gray-800 font-medium rounded-lg hover:bg-gray-50 hover:border-gray-400 transition-colors shadow-sm"
        >
          Back to List
        </Link>
      </div>
    </div>
  );
}