import { useStore } from '../store';
import './ProjectList.css';

export function ProjectList() {
  const { projects, currentProjectId, switchProject } = useStore();
  return (
    <div className="project-list">
      <div className="section-header">
        <span>Projects</span>
        <span className="section-meta">{projects.length}</span>
      </div>
      {projects.length === 0 ? (
        <div className="project-empty">No projects</div>
      ) : (
        <ul className="project-items">
          {projects.map((p) => (
            <li key={p.id} className={`project-item ${p.id === currentProjectId ? 'active' : ''}`} onClick={() => switchProject(p.id)} title={p.path}>
              <span className="project-icon">{p.id === currentProjectId ? '●' : '○'}</span>
              <span className="project-name">{p.name}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
