import { BrowserRouter as Router, Routes, Route, Link } from 'react-router-dom'
import DocumentsPage from './pages/DocumentsPage'
import DocumentViewPage from './pages/DocumentViewPage'
import DocumentEditPage from './pages/DocumentEditPage'
import DocumentCreatePage from './pages/DocumentCreatePage'
import ChatPage from './pages/ChatPage'
import './App.css'

function HomePage() {
  return (
    <div className="p-8 max-w-4xl mx-auto">
      <div className="bg-white rounded-xl shadow-lg p-8 border border-gray-200">
        <h1 className="text-4xl font-bold mb-4 text-gray-900">RAG Orchestrator UI</h1>
        <p className="mb-8 text-gray-700 text-lg">Welcome to the RAG Orchestrator test interface!</p>
        <div className="flex gap-4">
          <Link
            to="/documents"
            className="px-6 py-3 bg-green-600 text-white font-medium rounded-lg hover:bg-green-700 transition-colors shadow-md hover:shadow-lg"
          >
            Manage Documents
          </Link>
          <Link
            to="/chat"
            className="px-6 py-3 bg-blue-600 text-white font-medium rounded-lg hover:bg-blue-700 transition-colors shadow-md hover:shadow-lg"
          >
            AI Chat
          </Link>
        </div>
      </div>
    </div>
  )
}

function App() {
  return (
    <Router>
      <div className="min-h-screen bg-gradient-to-br from-gray-50 to-gray-100">
        <nav className="bg-gradient-to-r from-blue-600 to-indigo-700 text-white shadow-lg p-4">
          <div className="container mx-auto flex gap-6">
            <Link to="/" className="font-medium hover:text-blue-100 transition-colors">Home</Link>
            <Link to="/documents" className="font-medium hover:text-blue-100 transition-colors">Documents</Link>
            <Link to="/chat" className="font-medium hover:text-blue-100 transition-colors">Chat</Link>
          </div>
        </nav>
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/documents" element={<DocumentsPage />} />
          <Route path="/documents/new" element={<DocumentCreatePage />} />
          <Route path="/documents/:id" element={<DocumentViewPage />} />
          <Route path="/documents/:id/edit" element={<DocumentEditPage />} />
          <Route path="/chat" element={<ChatPage />} />
          <Route path="/chat/:conversationId" element={<ChatPage />} />
        </Routes>
      </div>
    </Router>
  )
}

export default App
