import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useDocuments } from '../hooks/useDocuments';
import DocumentTable from '../components/DocumentTable';
import SearchResults from '../components/SearchResults';
import api from '../services/api';
import type { SearchResult } from '../types/api.types';

export default function DocumentsPage() {
  const navigate = useNavigate();
  const { documents, loading, error, refresh, deleteDocument } = useDocuments();
  const [searchTerm, setSearchTerm] = useState('');
  const [filterCategory, setFilterCategory] = useState('all');
  const [deleteConfirm, setDeleteConfirm] = useState<string | null>(null);

  // Search-related state
  const [searchResults, setSearchResults] = useState<SearchResult[]>([]);
  const [searchLoading, setSearchLoading] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [isSearchMode, setIsSearchMode] = useState(false);

  // Debounced search function
  const performSearch = useCallback(async (query: string) => {
    if (!query.trim()) {
      setIsSearchMode(false);
      setSearchResults([]);
      setSearchError(null);
      return;
    }

    setSearchLoading(true);
    setSearchError(null);
    setIsSearchMode(true);

    try {
      const response = await api.searchDocuments(query.trim(), 20);
      setSearchResults(response.results);
      console.log(`Search completed: ${response.total} results in ${response.processing_time_ms}ms`);
    } catch (error) {
      console.error('Search failed:', error);
      setSearchError('Search failed. Please try again.');
      setSearchResults([]);
    } finally {
      setSearchLoading(false);
    }
  }, []);

  // Debounce search input
  useEffect(() => {
    const debounceTimer = setTimeout(() => {
      performSearch(searchTerm);
    }, 300);

    return () => clearTimeout(debounceTimer);
  }, [searchTerm, performSearch]);

  const handleView = (docId: string) => {
    navigate(`/documents/${docId}`);
  };

  const handleEdit = (docId: string) => {
    navigate(`/documents/${docId}/edit`);
  };

  const handleDelete = async (docId: string) => {
    if (deleteConfirm === docId) {
      try {
        await deleteDocument(docId);
        setDeleteConfirm(null);
        alert('Document deleted successfully');
      } catch (err) {
        console.error('Failed to delete document:', err);
        alert('Failed to delete document');
      }
    } else {
      setDeleteConfirm(docId);
      setTimeout(() => setDeleteConfirm(null), 3000); // Reset after 3 seconds
    }
  };

  // Navigation handlers for search results
  const handleViewFromSearch = (docId: string) => {
    navigate(`/documents/${docId}`);
  };

  // Filter documents when not in search mode (only category filter applies)
  const filteredDocuments = !isSearchMode ? (documents?.documents.filter(doc => {
    const matchesCategory = filterCategory === 'all' || doc.category === filterCategory;
    return matchesCategory;
  }) || []) : [];

  if (loading && !documents) {
    return (
      <div className="flex justify-center items-center h-64">
        <div className="text-gray-500">Loading documents...</div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="p-4">
        <div className="bg-red-50 border border-red-200 text-red-800 px-4 py-3 rounded-md">
          Error: {error}
        </div>
        <button
          onClick={refresh}
          className="mt-4 px-4 py-2 bg-blue-500 text-white rounded hover:bg-blue-600"
        >
          Retry
        </button>
      </div>
    );
  }

  // List view
  return (
    <div className="p-4 max-w-7xl mx-auto">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-3xl font-bold">Document Management</h1>
        <button
          onClick={() => navigate('/documents/new')}
          className="px-4 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700"
        >
          New Document
        </button>
      </div>

      {/* Filters */}
      <div className="bg-white p-4 rounded-lg shadow mb-6">
        <div className="grid grid-cols-3 gap-4">
          <div className="relative">
            <input
              type="text"
              placeholder={isSearchMode ? "Semantic search..." : "Search documents..."}
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="w-full px-3 py-2 pr-20 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
            {searchLoading && (
              <div className="absolute right-12 top-1/2 transform -translate-y-1/2">
                <div className="animate-spin h-4 w-4 border-2 border-blue-600 border-t-transparent rounded-full"></div>
              </div>
            )}
            {searchTerm && (
              <button
                onClick={() => setSearchTerm('')}
                className="absolute right-2 top-1/2 transform -translate-y-1/2 text-gray-400 hover:text-gray-600"
                title="Clear search"
              >
                <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            )}
          </div>
          <div>
            <select
              value={filterCategory}
              onChange={(e) => setFilterCategory(e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              <option value="all">All Categories</option>
              <option value="workflow">Workflow</option>
              <option value="faq">FAQ</option>
              <option value="reference">Reference</option>
              <option value="troubleshooting">Troubleshooting</option>
            </select>
          </div>
          <div className="flex items-center justify-end">
            <button
              onClick={refresh}
              className="px-4 py-2 bg-gray-200 text-gray-700 rounded hover:bg-gray-300"
            >
              Refresh
            </button>
          </div>
        </div>
      </div>

      {/* Search Results or Document Table */}
      {isSearchMode ? (
        <SearchResults
          results={searchResults}
          query={searchTerm}
          loading={searchLoading}
          error={searchError}
          onViewDocument={handleViewFromSearch}
        />
      ) : (
        <>
          {/* Document count */}
          <div className="mb-4 text-sm text-gray-600">
            Showing {filteredDocuments.length} of {documents?.total || 0} documents
          </div>

          {/* Document Table */}
          <DocumentTable
            documents={filteredDocuments}
            onView={handleView}
            onEdit={handleEdit}
            onDelete={handleDelete}
          />
        </>
      )}

      {/* Delete Confirmation */}
      {deleteConfirm && (
        <div className="fixed bottom-4 right-4 bg-red-50 border border-red-200 p-4 rounded-lg shadow-lg">
          <p className="text-red-800 mb-2">Delete document {deleteConfirm}?</p>
          <button
            onClick={() => handleDelete(deleteConfirm)}
            className="px-3 py-1 bg-red-600 text-white rounded mr-2 hover:bg-red-700"
          >
            Confirm Delete
          </button>
          <button
            onClick={() => setDeleteConfirm(null)}
            className="px-3 py-1 bg-gray-300 text-gray-700 rounded hover:bg-gray-400"
          >
            Cancel
          </button>
        </div>
      )}
    </div>
  );
}