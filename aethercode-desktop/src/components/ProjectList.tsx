// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

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
