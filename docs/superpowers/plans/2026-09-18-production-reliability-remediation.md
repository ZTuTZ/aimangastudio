# Production Reliability Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the confirmed task lifecycle, scoped generation, resume, result-versioning, post-processing, download, export, and production-safety defects from the 2026-09-18 audit.

**Architecture:** Keep MySQL as the task and business source of truth and Redis as a scheduling accelerator. Make scope an explicit immutable execution plan, require both Task and Stage Item ownership before committing work, and derive terminal status from current-version business results. Handle user-facing state through one TaskStatus policy shared by backend endpoints and frontend controls.

**Tech Stack:** Spring Boot 3 / MyBatis-Plus / MySQL / Redisson / JUnit 5 + Mockito / React + TypeScript + Vite.

## Global Constraints

- Modify and commit only files under `v2/`; do not stage parent-level files or `.github/`.
- Preserve the current MySQL Task + Redis queue + PipelineStage + PipelineStageItem design.
- Add a failing regression test before each behavior change and run its narrow test before implementation.
- Do not invoke real AI, OSS, or public URLs in tests.
- Treat a PAUSED task as a reserved user request: resume or stop only, retaining its original payload and completed work.
- An empty scoped target set is a no-op/error, never a project-wide run.

---

### Task 1: Repair the Mapper contract and establish mapper registration coverage

**Files:**
- Modify: `server/src/main/java/com/aimanga/v2/repository/TaskMapper.java:87-117`
- Create: `server/src/test/java/com/aimanga/v2/repository/TaskMapperRegistrationTest.java`

**Interfaces:**
- `TaskMapper.countFiltered(...)` owns the annotated COUNT query.
- `TaskMapper.pauseTask(id, claimToken)` owns only the PAUSED update query.

- [ ] Write a Mapper registration test that adds the real `TaskMapper` to a MyBatis `Configuration` and asserts no builder exception.
- [ ] Run that test and observe the current conflicting-annotation failure.
- [ ] Move the `@Select` annotation to `countFiltered`, leaving `pauseTask` with only `@Update`.
- [ ] Run the registration test and then the backend test suite.

### Task 2: Make generation scopes total, immutable, and consistent from request to claim

**Files:**
- Modify: `server/src/main/java/com/aimanga/v2/pipeline/StageRunScope.java`
- Modify: `server/src/main/java/com/aimanga/v2/pipeline/BatchTaskHandler.java`
- Modify: `server/src/main/java/com/aimanga/v2/pipeline/LayoutTaskHandler.java`
- Modify: `server/src/main/java/com/aimanga/v2/pipeline/PipelineStageService.java`
- Modify: `server/src/main/java/com/aimanga/v2/pipeline/ConcurrentStageRunner.java`
- Create: `server/src/test/java/com/aimanga/v2/pipeline/StageRunScopeTest.java`
- Extend: `server/src/test/java/com/aimanga/v2/pipeline/ConcurrentStageRunnerTest.java`

**Interfaces:**
- `StageRunScope.none(type)` represents an explicit empty scope.
- `StageRunScope.all()` is the only unbounded scope.
- `resetFailedItemsByBusiness(projectId, stageType, businessType, businessIds)` changes only the supplied units.

- [ ] Write tests proving an empty page/asset/chapter scope is not unbounded and cannot call the unscoped pending-item query.
- [ ] Run them to observe empty scopes currently select project-wide work.
- [ ] Add the explicit empty scope and make Runner return an empty result for it.
- [ ] Write a CHAPTERS BATCH test with non-target PENDING/FAILED Items and assert their IDs/statuses remain unchanged.
- [ ] Run it to observe the current full-scope/reset behavior.
- [ ] Make CHAPTER and CHAPTERS both page-scoped; permit orphan cleanup only for PROJECT; replace scoped global failed resets.
- [ ] Move RUNNING reset into the lock-held execution path and ensure no request mutates another live Attempt before acquiring the stage lock.
- [ ] Run scope and runner tests.

### Task 3: Make user requests appendable or conflict-safe instead of silently dropped

**Files:**
- Modify: `server/src/main/java/com/aimanga/v2/service/TaskService.java`
- Modify: `server/src/main/java/com/aimanga/v2/controller/PageController.java`
- Modify: `server/src/main/java/com/aimanga/v2/pipeline/MaterialGenerationService.java`
- Modify: `server/src/main/java/com/aimanga/v2/controller/ProjectController.java`
- Create: `server/src/test/java/com/aimanga/v2/service/TaskServiceRequestIdentityTest.java`

**Interfaces:**
- `ensureUniqueActiveTask` compares normalized type, scope and payload before reuse.
- Incompatible active requests return a 409 rather than returning a Task that cannot perform the new request.

- [ ] Write tests for a running PAGE request followed by another page and a CHAPTERS request followed by a different chapter set.
- [ ] Run them to show that the old Task is returned with its old payload.
- [ ] Normalize payloads and make incompatible active requests explicit conflicts; keep compatible retries idempotent.
- [ ] Validate task page/chapter/project associations and BATCH scope values at request entry.
- [ ] Run task service and controller-facing tests.

### Task 4: Fence Task ownership and every affected business write

**Files:**
- Modify: `server/src/main/java/com/aimanga/v2/task/TaskRuntime.java`
- Modify: `server/src/main/java/com/aimanga/v2/repository/TaskMapper.java`
- Modify: `server/src/main/java/com/aimanga/v2/task/TaskRunner.java`
- Modify: `server/src/main/java/com/aimanga/v2/pipeline/PageGenerationService.java`
- Modify: `server/src/main/java/com/aimanga/v2/pipeline/SheetTaskHandler.java`
- Modify: `server/src/main/java/com/aimanga/v2/pipeline/AssetRefTaskHandler.java`
- Extend: `server/src/test/java/com/aimanga/v2/task/TaskRunnerTest.java`
- Extend: `server/src/test/java/com/aimanga/v2/pipeline/StaleCommitFencingFailureInjectionTest.java`

**Interfaces:**
- `TaskRuntime` receives the claim token and throws a stale-task signal if ownership is no longer current.
- Progress and terminal writes include `id + claim_token + permitted status` in their database predicate.
- A stale Stage Item attempt makes no SUCCESS, FAILED, URL, history, or asset-status write.

- [ ] Write a failing test where a revoked task attempts a progress update and where stale image/asset work fails after a newer attempt succeeds.
- [ ] Run it to show ordinary `updateById` writes still occur.
- [ ] Add token-guarded progress/status mapper operations and route runtime persistence through them.
- [ ] Separate stale-commit rejection from operation failure in page and asset handlers.
- [ ] Run task and fencing tests.

### Task 5: Make watchdog, stop, pause, and resume state transitions atomic

**Files:**
- Modify: `server/src/main/java/com/aimanga/v2/repository/TaskMapper.java`
- Modify: `server/src/main/java/com/aimanga/v2/task/TaskWatchDog.java`
- Modify: `server/src/main/java/com/aimanga/v2/task/TaskStatus.java`
- Modify: `server/src/main/java/com/aimanga/v2/service/TaskService.java`
- Modify: `server/src/main/java/com/aimanga/v2/controller/TaskController.java`
- Modify: `server/src/main/java/com/aimanga/v2/controller/ProjectController.java`
- Extend: `server/src/test/java/com/aimanga/v2/task/TaskRunnerTest.java`
- Create: `server/src/test/java/com/aimanga/v2/task/TaskWatchDogTest.java`

**Interfaces:**
- `TaskStatus.reservesRequest(status)` includes PENDING, RUNNING, STOPPING and PAUSED.
- Watchdog requeue increments retry_count atomically and only when the observed lease is still revoked.
- `POST /api/tasks/{id}/resume` resumes only PAUSED tasks.

- [ ] Write failing tests for retry-count exhaustion, PAUSED task rejection by retry/delete, PAUSED stop, and direct resume.
- [ ] Run them to record current state-machine gaps.
- [ ] Add compare-and-set SQL for stop/retry/delete/resume and retry increment.
- [ ] Keep a STOPPING Worker leased while it drains, and prevent a terminal completion from overriding a successful stop.
- [ ] Sequence project resume so a task that finishes its pause drain is not missed.
- [ ] Run state-machine tests.

### Task 6: Resume only unfinished work and make versioned output current

**Files:**
- Modify: `server/src/main/java/com/aimanga/v2/pipeline/BatchTaskHandler.java`
- Modify: `server/src/main/java/com/aimanga/v2/pipeline/PageTaskHandler.java`
- Modify: `server/src/main/java/com/aimanga/v2/pipeline/LayoutTaskHandler.java`
- Modify: `server/src/main/java/com/aimanga/v2/pipeline/ProjectCompletionService.java`
- Modify: `server/src/main/java/com/aimanga/v2/pipeline/PublicationService.java`
- Extend: `server/src/test/java/com/aimanga/v2/pipeline/ProjectCompletionServiceTest.java`
- Create: `server/src/test/java/com/aimanga/v2/pipeline/VersionedGenerationPlanTest.java`

**Interfaces:**
- Resume detects its existing Task attempt and does not force-reset successful Item results.
- Current image/layout results require matching script versions.
- Single-page success refreshes chapter and project completion state.

- [ ] Write failing tests for force BATCH resume after partial completion and a SUCCESS Item whose page script version changed.
- [ ] Run tests to establish the repeat-charge and stale-output failures.
- [ ] Split initial request planning from resume execution; preserve counts and only enqueue unfinished/current-version units.
- [ ] Add version-aware reset/claim behavior and require current versions in completion and publication validation.
- [ ] Recalculate completion after single-page success and page lifecycle edits.
- [ ] Run generation and completion tests.

### Task 7: Repair per-chapter script regeneration and post-processing operation identity

**Files:**
- Modify: `server/src/main/java/com/aimanga/v2/controller/ChapterController.java`
- Modify: `server/src/main/java/com/aimanga/v2/pipeline/ScriptTaskHandler.java`
- Modify: `server/src/main/java/com/aimanga/v2/pipeline/CleanTaskHandler.java`
- Modify: `server/src/main/java/com/aimanga/v2/pipeline/RepaintTaskHandler.java`
- Modify: `server/src/main/java/com/aimanga/v2/pipeline/PostProcessTaskHandler.java`
- Create: `server/src/test/java/com/aimanga/v2/pipeline/PostProcessTaskHandlerTest.java`
- Create: `server/src/test/java/com/aimanga/v2/pipeline/ScriptTaskHandlerScopeTest.java`

**Interfaces:**
- Regenerating one chapter creates/resets that chapter’s SCRIPT Item and runs `StageRunScope.chapters(List.of(chapterId))`.
- CLEAN returns `OP_CLEAN`; REPAINT returns `OP_REPAINT`.

- [ ] Write failing tests for regenerated successful chapter work and for CLEAN/REPAINT service operation arguments.
- [ ] Run tests to show skipped script work and COLORIZE arguments.
- [ ] Reset only the requested script Item, invalidate its dependent page outputs safely, and use chapter Scope.
- [ ] Correct operation identity and reject concurrent same-page result writes through a page version/serialization rule.
- [ ] Run post-process and script scope tests.

### Task 8: Use one secure, bounded image transfer path and stream the real export response

**Files:**
- Modify: `server/src/main/java/com/aimanga/v2/pipeline/RemoteImageFetcher.java`
- Modify: `server/src/main/java/com/aimanga/v2/storage/OssStorageService.java`
- Modify: `server/src/main/java/com/aimanga/v2/pipeline/PublicationService.java`
- Modify: `server/src/main/java/com/aimanga/v2/controller/AdminExportController.java`
- Extend: `server/src/test/java/com/aimanga/v2/storage/OssStorageServiceTest.java`
- Create: `server/src/test/java/com/aimanga/v2/pipeline/RemoteImageFetcherTest.java`

**Interfaces:**
- Bounded fetch returns validated stream/mime and is shared by export and external-image persistence.
- Whitelisted hosts still reject loopback, link-local, private IPv4 and IPv6 ULA addresses.
- Batch export streams an outer ZIP and reports only entries actually written.

- [ ] Write failing fetch tests for localhost in allow list, `fd00::1`, over-limit content, missing MIME and redirect target validation.
- [ ] Run tests without calling public endpoints.
- [ ] Make host/IP validation cumulative, validate image bytes, and transfer into OSS/ZIP streams with byte limits.
- [ ] Replace nested byte-array packages with a streaming response or bounded temporary-file strategy; remove swallowed ZIP write errors and deduplicate IDs.
- [ ] Run storage/export tests.

### Task 9: Make Worker and permit recovery robust

**Files:**
- Modify: `server/src/main/java/com/aimanga/v2/task/TaskWorkerPool.java`
- Modify: `server/src/main/java/com/aimanga/v2/task/RedisConcurrencyLimiter.java`
- Modify: `server/src/main/java/com/aimanga/v2/service/ConfigService.java`
- Modify: `server/src/main/java/com/aimanga/v2/controller/AdminMonitorController.java`
- Extend: `server/src/test/java/com/aimanga/v2/task/TaskRunnerTest.java`
- Create: `server/src/test/java/com/aimanga/v2/task/TaskWorkerPoolTest.java`

**Interfaces:**
- Every WorkerHandle owns its active task state and recoverable queue errors do not terminate it.
- User permits renew with a live Task lease and expire after crash.

- [ ] Write a failing worker-loop test that simulates a recoverable queue exception and confirms subsequent task processing.
- [ ] Write a failing permit lifecycle test for a task exceeding ten minutes.
- [ ] Catch/retry queue and marker failures with bounded backoff; supervise exited workers.
- [ ] Store active state per WorkerHandle; make permit acquire/release/renew atomic or use the Task lease as authority.
- [ ] Run worker and limiter tests.

### Task 10: Make edits atomic and production bootstrap explicit

**Files:**
- Modify: `server/src/main/java/com/aimanga/v2/pipeline/TextLayerService.java`
- Modify: `server/src/main/java/com/aimanga/v2/service/PageService.java`
- Modify: `server/src/main/java/com/aimanga/v2/config/AdminBootstrap.java`
- Extend: `server/src/test/java/com/aimanga/v2/pipeline/TextLayerServiceTest.java`
- Create: `server/src/test/java/com/aimanga/v2/config/AdminBootstrapTest.java`

**Interfaces:**
- Text-layer saves validate all input before a transaction writes rows.
- Production requires explicitly supplied initial-admin credentials; development-only defaults are opt-in.

- [ ] Write a failing text-layer test with a valid first element and invalid later element, asserting no partial mutation.
- [ ] Write a failing bootstrap test for production without explicit credentials.
- [ ] Add transactional validation/write boundaries and atomic page script/version/binding update handling.
- [ ] Restrict default bootstrap credentials to an explicit development profile/configuration and never log a password.
- [ ] Run text-layer and bootstrap tests.

### Task 11: Complete task UI semantics and production validation

**Files:**
- Modify: `frontend/src/api/tasks.ts`
- Modify: `frontend/src/pages/tasks/TaskCenter.tsx`
- Modify: `frontend/src/pages/admin/TaskMonitor.tsx`
- Modify: `frontend/src/components/GenerationWorkbench.tsx`
- Modify: `docs/AIManga_v2_系统稳定性与生产化续审报告_2026-09-18.md`

**Interfaces:**
- `TASK_STATUS[7]` is “已暂停”; `tasksApi.resume(id)` calls the backend endpoint.
- PAUSED reserves a batch slot and exposes resume/stop, not retry/delete.

- [ ] Add a failing TypeScript-level assertion/build check for Task status 7 and resume API use.
- [ ] Implement PAUSED labels, active/reserved state, action buttons, monitoring fields, and state-aware polling.
- [ ] Run frontend build.
- [ ] Run all available backend tests, report any environment-limited checks, and update the audit report with completed/remaining evidence.
- [ ] Commit only `v2/` paths in focused commits, with no push unless separately requested.
