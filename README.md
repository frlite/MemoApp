# 备忘录

一个简洁的安卓备忘录应用，支持文字朗读。

## 功能

- **列表浏览**：按最后修改时间倒序展示全部备忘录
- **快速新建**：右下角悬浮按钮新建，直接进入编辑
- **自动保存**：退出编辑页时自动保存，无需手动点保存按钮
- **删除单条**：列表项右侧删除按钮，带确认弹窗
- **清空全部**：工具栏菜单，带确认弹窗
- **标题预览**：自动取正文第一行作为标题，无需单独填标题

### 朗读功能

- **循环朗读**：读完后自动从头继续，直到手动停止
- **后台播放**：使用前台服务，返回列表或切到后台后继续朗读
- **语速 / 音调调节**：0.5x ~ 2.0x，逐条备忘录独立保存
- **通知栏控制**：通知栏可直接停止朗读

## 技术栈

| 组件 | 说明 |
|------|------|
| Kotlin | 开发语言 |
| Room | 本地数据库 |
| RecyclerView + ListAdapter | 列表展示 |
| TextToSpeech + 前台服务 | 文字朗读与循环播放 |
| ViewBinding | 视图绑定 |
| Material 3 | UI 组件 |
| Coroutines + Flow | 异步与数据流 |

## 构建

```bash
./gradlew assembleDebug
```

产物位于 `app/build/outputs/apk/debug/`

也可在 GitHub Actions 中查看构建产物：Actions 标签页 → 最新构建 → Artifacts。

## 项目结构

```
app/src/main/java/com/example/memoapp/
├── MemoListActivity.kt      # 列表页
├── MemoDetailActivity.kt    # 编辑 + 朗读页
├── TtsService.kt            # 朗读前台服务（循环播放）
├── adapter/
│   └── MemoAdapter.kt       # 列表适配器
└── data/
    ├── MemoEntity.kt        # 备忘录数据模型
    ├── MemoDao.kt           # 数据库操作接口
    └── MemoDatabase.kt      # 数据库单例
```

## 数据模型

```kotlin
@Entity(tableName = "memos")
data class MemoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "",      // 预留，当前用正文首行作为标题
    val content: String = "",
    val speechRate: Float = 1.0f,   // 朗读语速
    val pitch: Float = 1.0f,        // 朗读音调
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
```

## 后续可扩展方向

- 搜索与标签分类
- 置顶功能
- 分享为文本
- 回收站（软删除）
- 纯文字以外的富文本编辑
