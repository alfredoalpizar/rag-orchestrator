import { useNavigate } from 'react-router-dom';
import { useDocuments } from '../hooks/useDocuments';
import DocumentForm from '../components/DocumentForm';
import type { Document } from '../types/api.types';

export default function DocumentCreatePage() {
  const navigate = useNavigate();
  const { createDocument } = useDocuments();

  const handleSubmit = async (doc: Document) => {
    try {
      const createdDoc = await createDocument(doc);
      alert('Document created successfully');
      // Navigate to the newly created document's view page
      navigate(`/documents/${createdDoc.metadata.id}`);
    } catch (err) {
      console.error('Failed to create document:', err);
      throw err; // Let DocumentForm handle the error display
    }
  };

  const handleCancel = () => {
    navigate('/documents'); // Navigate back to documents list
  };

  return (
    <div className="max-w-4xl mx-auto p-4">
      <h1 className="text-2xl font-bold mb-6">Create New Document</h1>
      <DocumentForm
        onSubmit={handleSubmit}
        onCancel={handleCancel}
        isEdit={false}
      />
    </div>
  );
}