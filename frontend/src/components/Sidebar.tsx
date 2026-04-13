import { NavLink } from 'react-router-dom'

const links = [
  { to: '/', label: 'Dashboard' },
  { to: '/students', label: 'Students' },
  { to: '/teachers', label: 'Teachers' },
  { to: '/attendance', label: 'Attendance' },
  { to: '/notifications', label: 'Notifications' },
]

export default function Sidebar() {
  return (
    <aside className="w-48 bg-gray-900 text-white flex flex-col shrink-0">
      <div className="px-4 py-5 text-lg font-bold border-b border-gray-700">
        School Manager
      </div>
      <nav className="flex flex-col mt-2">
        {links.map(({ to, label }) => (
          <NavLink
            key={to}
            to={to}
            end={to === '/'}
            className={({ isActive }) =>
              `px-4 py-3 text-sm font-medium transition-colors ${
                isActive
                  ? 'bg-blue-600 text-white'
                  : 'text-gray-300 hover:bg-gray-700 hover:text-white'
              }`
            }
          >
            {label}
          </NavLink>
        ))}
      </nav>
    </aside>
  )
}
