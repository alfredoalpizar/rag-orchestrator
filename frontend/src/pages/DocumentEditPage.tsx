import { useState, useEffect } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { useDocuments } from '../hooks/useDocuments';
import DocumentForm from '../components/DocumentForm';
import type { Document, DocumentResponse } from '../types/api.types';

export default function DocumentEditPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { getDocument, updateDocument } = useDocuments();
  const [document, setDocument] = useState<DocumentResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

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
      console.log('Loaded document for editing:', doc);
      setDocument(doc);
    } catch (err) {
      console.error('Failed to load document:', err);
      setError('Failed to load document for editing');
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = async (doc: Document) => {
    if (!id || !document) return;

    try {
      // Get the ETag from the response headers if available
      const etag = document.storageMetadata?.lastModified
        ? `"${new Date(document.storageMetadata.lastModified).getTime()}"`
        : undefined;

      await updateDocument(id, doc, etag);
      alert('Document updated successfully');
      navigate(`/documents/${id}`); // Navigate to view page after successful edit
    } catch (err) {
      console.error('Failed to update document:', err);
      throw err; // Let DocumentForm handle the error display
    }
  };

  const handleCancel = () => {
    navigate(`/documents/${id}`); // Navigate back to view page
  };

  if (loading) {
    return (
      <div className="flex justify-center items-center h-64">
        <div className="text-gray-500">Loading document...</div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="p-4">
        <div className="bg-red-50 border border-red-200 text-red-800 px-4 py-3 rounded-md">
          Error: {error}
        </div>
        <Link
          to="/documents"
          className="mt-4 inline-block px-4 py-2 bg-blue-500 text-white rounded hover:bg-blue-600"
        >
          Back to Documents
        </Link>
      </div>
    );
  }

  if (!document) {
    return (
      <div className="p-4">
        <div className="text-gray-500">Document not found</div>
        <Link
          to="/documents"
          className="mt-4 inline-block px-4 py-2 bg-blue-500 text-white rounded hover:bg-blue-600"
        >
          Back to Documents
        </Link>
      </div>
    );
  }

  return (
    <div className="max-w-4xl mx-auto p-4">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold">Edit Document</h1>
        <Link
          to={`/documents/${id}`}
          className="px-4 py-2 bg-gray-200 text-gray-700 rounded hover:bg-gray-300"
        >
          Cancel
        </Link>
      </div>

      <DocumentForm
        document={document}
        onSubmit={handleSubmit}
        onCancel={handleCancel}
        isEdit={true}
      />
    </div>
  );
}