import { useState, useEffect } from 'react';
import type { Document, DocumentResponse } from '../types/api.types';
import MarkdownEditor from './MarkdownEditor';

interface DocumentFormProps {
  document?: DocumentResponse | null;
  onSubmit: (doc: Document) => Promise<void>;
  onCancel: () => void;
  isEdit?: boolean;
}

export default function DocumentForm({ document, onSubmit, onCancel, isEdit = false }: DocumentFormProps) {
  const [formData, setFormData] = useState<Document>({
    metadata: {
      id: '',
      title: '',
      category: 'reference',
      tags: [],
      status: 'draft'
    },
    content: {
      summary: '',
      body: ''
    }
  });

  const [errors, setErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (document) {
      setFormData({
        metadata: {
          id: document.metadata.id,
          title: document.metadata.title,
          category: document.metadata.category,
          tags: document.metadata.tags || [],
          status: document.metadata.status
        },
        content: {
          summary: document.content.summary || '',
          body: document.content.body
        }
      });
    }
  }, [document]);

  const validateForm = (): boolean => {
    const newErrors: Record<string, string> = {};

    if (!formData.metadata.id.trim()) {
      newErrors.id = 'Document ID is required';
    }
    if (!formData.metadata.title.trim()) {
      newErrors.title = 'Title is required';
    }
    if (!formData.content.body.trim()) {
      newErrors.body = 'Content body is required';
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!validateForm()) {
      return;
    }

    setSubmitting(true);
    try {
      await onSubmit(formData);
    } catch (error) {
      console.error('Failed to submit document:', error);
      setErrors({ submit: error instanceof Error ? error.message : 'Failed to save document' });
    } finally {
      setSubmitting(false);
    }
  };

  const handleTagsChange = (value: string) => {
    const tags = value.split(',').map(t => t.trim()).filter(t => t);
    setFormData(prev => ({
      ...prev,
      metadata: { ...prev.metadata, tags }
    }));
  };

  const generateDocId = () => {
    const timestamp = Date.now();
    const random = Math.random().toString(36).substr(2, 5);
    setFormData(prev => ({
      ...prev,
      metadata: { ...prev.metadata, id: `doc-${timestamp}-${random}` }
    }));
  };


  return (
    <form onSubmit={handleSubmit} className="space-y-6">
      {/* Metadata Section */}
      <div className="bg-white p-6 rounded-lg shadow">
        <h3 className="text-lg font-medium mb-4">Document Metadata</h3>

        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Document ID *
            </label>
            <div className="flex">
              <input
                type="text"
                value={formData.metadata.id}
                onChange={(e) => setFormData(prev => ({
                  ...prev,
                  metadata: { ...prev.metadata, id: e.target.value }
                }))}
                disabled={isEdit}
                className="flex-1 px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:bg-gray-100"
                placeholder="e.g., doc-123"
              />
              {!isEdit && (
                <button
                  type="button"
                  onClick={generateDocId}
                  className="ml-2 px-3 py-2 bg-gray-200 text-gray-700 rounded-md hover:bg-gray-300"
                >
                  Generate
                </button>
              )}
            </div>
            {errors.id && <p className="mt-1 text-sm text-red-600">{errors.id}</p>}
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Title *
            </label>
            <input
              type="text"
              value={formData.metadata.title}
              onChange={(e) => setFormData(prev => ({
                ...prev,
                metadata: { ...prev.metadata, title: e.target.value }
              }))}
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
              placeholder="Document title"
            />
            {errors.title && <p className="mt-1 text-sm text-red-600">{errors.title}</p>}
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Category *
            </label>
            <select
              value={formData.metadata.category}
              onChange={(e) => setFormData(prev => ({
                ...prev,
                metadata: { ...prev.metadata, category: e.target.value }
              }))}
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              <option value="workflow">Workflow</option>
              <option value="faq">FAQ</option>
              <option value="reference">Reference</option>
              <option value="troubleshooting">Troubleshooting</option>
            </select>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Status *
            </label>
            <select
              value={formData.metadata.status}
              onChange={(e) => setFormData(prev => ({
                ...prev,
                metadata: { ...prev.metadata, status: e.target.value as 'published' | 'draft' | 'archived' }
              }))}
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              <option value="draft">Draft</option>
              <option value="published">Published</option>
              <option value="archived">Archived</option>
            </select>
          </div>

          <div className="col-span-2">
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Tags (comma-separated)
            </label>
            <input
              type="text"
              value={formData.metadata.tags?.join(', ') || ''}
              onChange={(e) => handleTagsChange(e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
              placeholder="e.g., api, integration, guide"
            />
          </div>
        </div>
      </div>

      {/* Content Section */}
      <div className="bg-white p-6 rounded-lg shadow">
        <h3 className="text-lg font-medium mb-4">Document Content</h3>

        <div className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Summary (optional)
            </label>
            <textarea
              value={formData.content.summary}
              onChange={(e) => setFormData(prev => ({
                ...prev,
                content: { ...prev.content, summary: e.target.value }
              }))}
              rows={2}
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
              placeholder="Brief 1-2 sentence description"
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Body (Markdown) *
            </label>
            <MarkdownEditor
              value={formData.content.body}
              onChange={(value) => setFormData(prev => ({
                ...prev,
                content: { ...prev.content, body: value }
              }))}
              placeholder="Enter document content in Markdown format..."
              height={400}
              error={errors.body}
            />
          </div>
        </div>
      </div>

      {/* Error message */}
      {errors.submit && (
        <div className="bg-red-50 border border-red-200 text-red-800 px-4 py-3 rounded-md">
          {errors.submit}
        </div>
      )}

      {/* Form Actions */}
      <div className="flex justify-end space-x-4">
        <button
          type="button"
          onClick={onCancel}
          className="px-4 py-2 border border-gray-300 rounded-md text-gray-700 hover:bg-gray-50"
        >
          Cancel
        </button>
        <button
          type="submit"
          disabled={submitting}
          className="px-4 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {submitting ? 'Saving...' : isEdit ? 'Update Document' : 'Create Document'}
        </button>
      </div>
    </form>
  );
}