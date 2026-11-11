import { createRoot } from 'react-dom/client';
import StudentApp from './StudentApp.jsx';
import '../styles.css';
import './student.css';

createRoot(document.getElementById('root')).render(<StudentApp />);
