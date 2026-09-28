# Soft Gradient Frontend Implementation Plan

> **For agentic workers:** Use the approved A direction from the visual comparison. Execute the tasks in this session; preserve existing behavior and APIs.

**Goal:** Apply a coherent light, soft-gradient creative-workspace design to every frontend route and its shared dialogs/workbenches.

**Architecture:** Put color, typography, spacing, and Ant Design component styling in shared theme tokens and global CSS. Give each route a consistent page shell/header while tailoring the work areas for project browsing, content editing, tasks, and administration. Keep workflow logic and API calls intact.

**Tech Stack:** React 18, Ant Design 5, Tailwind CSS 3, TypeScript, Vite.

## Global Constraints

- The selected style is A: near-white background, restrained lavender and peach glows, clear opaque working surfaces, iris-purple primary actions.
- All routes must receive the visual treatment: login, project list/detail/page editor, task center, settings, all administration routes, placeholder and not-found screens.
- Preserve current API calls, route paths, form validation, generation behavior, and status meaning.
- Dense tables and editing canvases stay readable; gradients serve as atmosphere, not text surfaces.
- Layout must remain usable at desktop and narrow viewport widths.

---

### Task 1: Shared visual foundation

**Files:** `frontend/src/theme/token.ts`, `frontend/src/index.css`, `frontend/src/studio.css`, `frontend/src/main.tsx`, `frontend/src/layouts/BasicLayout.tsx`

- [x] Define consistent color and component tokens for surfaces, typography, borders, input, table, tabs, and buttons.
- [x] Style the app shell with a subtle gradient canvas, clearer navigation, responsive spacing, and visible focus states.
- [x] Apply shared styling to Ant Design cards, tables, forms, menus, dialogs, statuses, and empty states.

### Task 2: Creative routes

**Files:** `frontend/src/pages/Login.tsx`, `frontend/src/pages/projects/ProjectList.tsx`, `frontend/src/pages/projects/ProjectDetail.tsx`, `frontend/src/pages/projects/PageDetail.tsx`, `frontend/src/components/GenerationWorkbench.tsx`, `frontend/src/components/TextLayerEditor.tsx`, `frontend/src/components/UploadStoriesModal.tsx`

- [x] Give login, works, project, and page-editing screens a shared hierarchy and page headers.
- [x] Bring project covers and generated images forward while keeping controls and text on opaque surfaces.
- [x] Align upload, generation, page gallery, and text editor subpanels with the visual foundation.

### Task 3: Operational and secondary routes

**Files:** `frontend/src/pages/tasks/TaskCenter.tsx`, `frontend/src/pages/settings/ChangePassword.tsx`, `frontend/src/pages/admin/*.tsx`, `frontend/src/pages/NotFound.tsx`, `frontend/src/components/ComingSoon.tsx`

- [x] Apply page headers, sections, spacing, and readable table/form treatments to each route.
- [x] Keep task and export status cues semantically distinct and legible.
- [x] Make placeholder and error screens feel like the same product.

### Task 4: Verification

- [x] Run frontend typecheck, tests, and production build.
- [x] Inspect route coverage and the final diff for accidental behavior changes.
- [x] Visually inspect representative screens at desktop and narrow widths where possible.
