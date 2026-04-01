# Android-GroupList

这是一个 Android 分组列表库，用于简化 RecyclerView 分组场景的开发。

## 项目结构

```
Android-GroupList/
├── app/                    # 示例应用
│   └── src/main/java/io/github/xesam/android/grouplist/example/
│       ├── data/repository/   # 数据仓库（模拟后端数据）
│       └── ui/                # UI 层（Activity + ViewModel + Adapter）
├── grouplist-core/         # 库模块：纯 JVM 核心（vnext 全部：shared/core/engine）
├── grouplist-recyclerview/ # 库模块：RecyclerView 适配层（binder/adapter/delegate）
├── grouplist-compose/      # 库模块：Compose 适配层（GroupListState + GroupListLazyColumn）
└── docs/                   # 文档
    └── how-it-works.md        # 库内部原理说明
```

## 构建与测试

### 运行测试

```bash
# core 是纯 JVM 模块，任务名为 test；两个 Android 模块为 testDebugUnitTest
./gradlew :grouplist-core:test :grouplist-recyclerview:testDebugUnitTest :grouplist-compose:testDebugUnitTest --no-daemon
```

### 构建示例应用

```bash
./gradlew :app:assembleDebug --no-daemon
```

### 发布到 Maven Local

```bash
./gradlew :grouplist-core:publishToMavenLocal :grouplist-recyclerview:publishToMavenLocal :grouplist-compose:publishToMavenLocal --no-daemon
```

发布坐标：
- `groupId`: `io.github.xesam`
- `android-grouplist-core`: 核心层（纯 JVM）
- `android-grouplist-recyclerview`: RecyclerView 适配层
- `android-grouplist-compose`: Compose 适配层（GroupListState + GroupListLazyColumn）
- `version`: `0.0.1`

## 理解项目的推荐路径

第一次接触这个项目时，建议按以下顺序阅读代码：

1. `app/src/main/java/io/github/xesam/android/grouplist/example/data/repository/FeedRepository.java`
   - 了解数据从哪里来（模拟后端数据）

2. `app/src/main/java/io/github/xesam/android/grouplist/example/ui/mapper/FeedRowMapper.java`
   - 了解如何将后端数据转换成 UI 语义对象

3. `app/src/main/java/io/github/xesam/android/grouplist/example/ui/common/BaseDemoViewModel.java`
   - 了解页面状态和交互逻辑的管理

4. `app/src/main/java/io/github/xesam/android/grouplist/example/ui/engine/EngineDemoViewModel.java`
   - 了解如何配置 Engine 并提交数据（推荐的接入方式）

5. `app/src/main/java/io/github/xesam/android/grouplist/example/ui/common/DemoUiAdapter.java`
   - 了解如何将快照渲染到 RecyclerView

完整数据流：
```
Repository → Mapper → ViewModel.sourceRows → EngineController → ListSnapshot → Adapter → RecyclerView
```

## 核心概念

### Engine vs Core

- **Engine**（推荐）：提交业务对象，库自动根据 `GroupSchema` 转换成内部行结构
  - 示例：`EngineDemoViewModel.java`
  - 适合：普通业务页面

- **Core**（进阶）：手动构造 `CoreRow`，完全控制每一行的结构
  - 示例：`CoreDemoViewModel.java`
  - 适合：需要精确控制行结构的场景

### GroupSchema

定义分组对象的结构映射（对应 `GroupSchema` 的 6 个方法）：
- `keyOf`：分组的唯一标识
- `headerOf`：分组头部数据
- `childrenOf`：分组子项列表
- `footerOf`：分组尾部数据（返回 null 即"无 footer"）
- `initiallyExpanded`：默认是否展开（仅在 submit 时作为初始值，live 展开态由 controller 持有）
- `showFooterWhenCollapsed`：收起时是否仍显示 footer

### ViewType 注册

Engine 需要注册所有会出现在页面上的对象类型：
- `registerStandaloneViewType(...)`：独立行（非分组行）
- `registerGroupHeaderViewType(...)`：分组头
- `registerGroupItemViewType(...)`：分组子项
- `registerGroupFooterViewType(...)`：分组尾

### RecyclerView 双入口（继承 / 组合）

`SnapshotAdapterDelegate` 是唯一真实实现（行存储、版本门、ChangeSet 翻译、GroupState 缓存），两条接入路径共享它：

- **继承**（默认路径）：`GroupListRecyclerAdapter` 是薄转发门面，protected 钩子（`requestSetGroupExpanded`、`notifyXxxCompat`）原位保留；通知经内部桥接流经钩子，子类覆写零感知生效
- **组合**（存量基类场景）：用户的 `extends` 名额被自家基类占用时，自有 Adapter 持有 delegate + `AdapterNotifier(this)`，转发四个 Adapter 方法，展开意图经 `setOnExpansionRequest` 回调上抛
- 回调-覆写无优先级规则是结构保证（门面不暴露 delegate），不是文档约定；门面与 delegate 共享唯一实现，两套测试分别钉住只有本入口会坏的接线（facade：`CompatNotifier` → protected 钩子桥接；delegate：Notifier 回调通路与 `setOnExpansionRequest`），而非两份实现互证等价
- group 状态推导已下沉 core 模块（`vnext.shared.GroupState` / `GroupStates`），作为供两个适配层共用的单一事实源（Compose 侧待真实消费场景激活查询 API）；`getGroupState` 因此返回 shared 类型（原 `GroupListRecyclerAdapter.GroupState` 已迁址）
- 继承面幸存钩子：`requestSetGroupExpanded`、`notifyXxxCompat` 族、`dispatchNotifyDataSetChanged`；原 `protected submitRows` 钩子已移除（零调用者，delegate 中无对应拦截点），覆写它不再生效

### 状态管理契约

- **展开状态**：由 Controller 层持有，可通过 `isGroupExpanded(key)` 查询；状态 map 仅 submit 时全清、跨增量命令保留，key 必须是稳定业务域身份（key 基数即 map 上界）
- **双水道（公开契约）**：
  - `submit` = 重载：收平全部展开状态，适合换数据集/首次加载
  - 增量通道 = 保全：展开意图跨命令、跨隐藏存活，适合一切未换数据集的变化
- **增量更新**：使用 `EngineCommand` 进行局部更新
  - `ToggleGroup` / `SetGroupExpanded`：展开/收起分组
  - `ReplaceChildren`：替换分组子项（隐藏期间 override 静默缓存）
  - `SetVisibilityFilter`：切换可见性过滤（块级精确差分，保展开）
  - `UpdateStandalone`：standalone 原位内容替换（精确 UPDATE）
- **ChangeSet 坐标系**：顺序应用语义（同 DiffUtil 惯例），等价性由顺序应用不变量测试公证
  （`ChangeSetApplicabilityTest` / `ChangeSetApplicabilityFuzzTest` 对旧 rows 逐条模拟应用并与新快照
  逐元素比对；`DefaultCoreControllerPerfGuardTest` 只做重负载终态一致性 +
  时限护栏，不做应用等价性）；新增生产者通道必须维护该不变量
- **消费协议强制**：交付给 RecyclerView adapter 的每一帧快照之后必须 `takeChangeSet()` drain；
  未 drain 的增量会在下一帧复合（incremental-after-incremental compounds）并被重复应用，
  真 RecyclerView 以 "Inconsistency detected" 崩溃（demo 曾因此崩溃，回归见
  `ChangeSetConsumptionContractTest.undrainedIncremental_compoundsIntoNextFrameAndDoublesOnSecondFrame`）

## 代码风格

- 语言：Java（core / recyclerview / app）；Compose 适配层与 Compose 示例为 Kotlin
- 命名：驼峰命名法
- 注释：关键逻辑需要注释说明
- 测试：核心逻辑需要单元测试覆盖

## 关键测试文件

- `grouplist-core/src/test/java/io/github/xesam/android/grouplist/vnext/core/DefaultCoreControllerTest.java`
- `grouplist-core/src/test/java/io/github/xesam/android/grouplist/vnext/core/DefaultCoreControllerReconcileTest.java`
- `grouplist-core/src/test/java/io/github/xesam/android/grouplist/vnext/core/DefaultCoreControllerReplaceRowsTest.java`
- `grouplist-core/src/test/java/io/github/xesam/android/grouplist/vnext/engine/DefaultEngineControllerTest.java`
- `grouplist-recyclerview/src/test/java/io/github/xesam/android/grouplist/recyclerview/delegate/SnapshotAdapterDelegateTest.java`（delegate 语义主套件；ChangeSet.Type → 通知映射另见 `ChangeSetAdapterCoverageTest`）
- `grouplist-recyclerview/src/test/java/io/github/xesam/android/grouplist/recyclerview/adapter/GroupListRecyclerAdapterTest.java`（门面套件：钉 `CompatNotifier` → protected 钩子接线，以及公开查询 / 展开 API 到 delegate 的转发）

已知测试缺口：`AdapterNotifier` 的转发依赖 `RecyclerView.Adapter` 的真实 notify 家族，其 `android.database.Observable` 在本地 JVM 单测中未打桩，而本模块刻意不引入 Robolectric（同一"无 Robolectric 依赖"性质另见 `ItemBinderRegistry` 注释——那里为 LinkedHashMap vs SparseArray 的取舍论证了 android 框架类在本地 JVM 未打桩），故该转发目前**没有任何测试覆盖**：没有 JVM 单测，也没有 instrumentation 测试（两个模块的 `androidTest` 目录为空，无源码）。它只有源码审阅兜底。

## 提交与 PR 规范

- 提交信息格式：`<type>(<scope>): <description>`
  - `type`：feat / fix / refactor / test / docs / perf
  - `scope`：模块名称（可选）
  - `description`：简短描述

示例：
- `feat(engine): 添加动态 viewType 解析器`
- `fix(core): 修复展开状态同步问题`
- `refactor(state): 统一状态收敛为 Core 单一事实源`
- `test(perf-guard): 添加重负载结构不变量护栏`

## 架构原则

1. **单一事实源**：展开状态、快照数据等关键状态由 Controller 层统一管理
2. **业务与渲染分离**：业务对象（如 `GroupUiModel`）与渲染快照（`ListSnapshot`）分开
3. **精确增量更新**：尽量使用 `ReplaceChildren` 等精确增量命令，避免 `notifyDataSetChanged`
4. **性能优先**：关注分段复用、索引优化等性能细节
