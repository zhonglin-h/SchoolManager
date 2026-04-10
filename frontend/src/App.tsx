import { BrowserRouter, Routes, Route } from 'react-router-dom'
import Sidebar from './components/Sidebar'
import TopBar from './components/TopBar'
import Dashboard from './pages/Dashboard'
import Students from './pages/Students'
import Teachers from './pages/Teachers'
import Notifications from './pages/Notifications'

export default function App() {
  return (
    <BrowserRouter>
      <div className="flex h-screen bg-gray-100">
        <Sidebar />
        <div className="flex flex-col flex-1 overflow-hidden">
          <TopBar />
          <main className="flex-1 overflow-y-auto p-6">
            <Routes>
              <Route path="/" element={<Dashboard />} />
              <Route path="/students" element={<Students />} />
              <Route path="/teachers" element={<Teachers />} />
              <Route path="/notifications" element={<Notifications />} />
            </Routes>
          </main>
        </div>
      </div>
    </BrowserRouter>
  )
}
