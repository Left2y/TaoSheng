# A 线：自定义转写句级时间戳

依据：2026-11-11《任务书-涛声自定义转写认人》第 2、3 节。

## 实现

- `ConfiguredAsrProvider.kt`：始终读取响应中的有效 `segments`，转换成 `AsrSentence`。秒乘 1000 后取整为毫秒，限制在音频时长范围内；丢弃空文本、缺失或非有限时间、原始结束时间早于开始时间的段。无有效段时沿用顶层 `text`，原 MiniMax `base_resp` 错误处理函数不变。仅在 `separateSpeakers` 开启时保留 `custom:<speaker>` 标签。
- `AndroidTranscribeSettings.kt`：增加默认关闭的 `customVerboseJson` 状态和 `setCustomVerboseJson`，沿用 `customPrompt` 的 SharedPreferences 同步保存、StateFlow 和变更通知方式。
- `CustomModelForm.kt`：在自定义转写的自动匹配、完整地址两种模式下显示“请求句级时间戳（verbose_json）”开关。
- `TranscriptScreen.kt`：向 `EndpointModeUi` 传入开关状态和保存回调。
- `CustomVerboseJsonTest.kt`：新增 10 个测试。

开关关闭时继续请求 `response_format=json`；打开时改为 `verbose_json` 并增加 `timestamp_granularities[]=segment`。解析不受开关控制，服务只返回 `text` 时仍能正常使用。录音、声纹、豆包、本地模型逻辑及约定文件签名均未修改。

## 新增测试

1. 多句解析、秒转毫秒及 `custom:` speaker 前缀。
2. 说话人开关、可选 speaker 和数字 speaker。
3. 时间截断、毫秒取整及文本去首尾空白。
4. 丢弃非法段并保留有效段，含截断或取整前已经倒序的段。
5. 空 segments 或所有段无效时回退顶层正文。
6. 纯 `text` 和空白正文保持原行为。
7. MiniMax 鉴权、协议错误仍使用原固定提示。
8. 两种地址模式默认关闭时请求体与旧实现逐字节一致（使用相同 multipart boundary），同时仍解析 segments。
9. 两种地址模式打开开关后字段正确，并兼容仅返回 `text` 的服务。
10. 默认关闭、修改通知及重新创建设置实例后保留开关开/关状态。

## 验证

接手后核对完整 diff：解析始终启用；speaker 受 `separateSpeakers` 控制；请求开关默认关闭并持久化；两种地址模式显示同一个开关；关闭时保持旧请求；MiniMax 错误分支保持不变。未发现需要补改的功能代码。

执行 `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew testDebugUnitTest --console=plain`，耗时 3 分 25 秒：1097 项，1072 通过、17 失败、8 跳过，命令退出码 1。因此尚未达到“全量测试全部通过”。完整输出：任务目录 `outputs/A-testDebugUnitTest.log`。

本卡相关 44 项全部通过：`CustomVerboseJsonTest` 10 项、`CustomFullEndpointTest` 7 项、`CustomHttp055Test` 8 项、`CustomModelsTest` 3 项、`Hotword066Test` 8 项、`Failure065Test` 8 项。没有跳过或排除失败测试，也没有为通过测试修改无关业务逻辑。

负责人独立对未修改的 `upstream/main` 基线执行全量测试，结果为 1087 项、16 项失败；清单见任务目录 `outputs/A-上游基线已有失败.txt`。已逐项核对：A 线全量结果中的以下 16 项与该基线一致：

| 测试类 | 失败用例 |
| --- | --- |
| `AskToolsTest` | `allToolsFilterOrEscapingTimeSpeakerDeletedAndCap` |
| `BackupPrompts029Test` | `disablingEncryptionChangesNextUploadAndKeepsDiaryAndSnapshotCleanup` |
| `BackupPrompts029Test` | `templatesReadDefaultsAndPersistOverridesInBackup` |
| `DiaryBackupTest` | `failedDiaryUploadKeepsSuccessfulBackupAndRetriesBothDaysNextNight` |
| `LocalAsrFailure063Test` | `exportedProblemReportContainsExceptionClassButNoMessageOrPath` |
| `LocalAsrFailure063Test` | `missingAudioIsSpecificAndDoesNotStopLaterSegmentsOrRetryForever` |
| `LocalAsrPolicyTest` | `rejectCredentialsAndUnsafeSchemes` |
| `ManualReview058Test` | `missingKeyAndModelErrorsUseSafeDatedReasons` |
| `PushChannelsTest` | `checksVendorCodesRedirectsMalformedAndOversizedResponses` |
| `ReviewInputLimit101Test` | `capacitiesParseFromAssetsAndOldFormatDefaults` |
| `Starred041Test` | `weeklyVersionTwoIncludesOldEventsStarredThisWeekAndItemProgress` |
| `Subscription042Test` | `fullWebhookAndTelegramUseDailyMemoryWithMarkdownAttachment` |
| `Subscription042Test` | `linkedCredentialsAreLiveNotCopiedAndFolderConflictRejected` |
| `Subscription042Test` | `schedulingCombinesMinimumGapMultipleTimesWeekdaysAndManual` |
| `Subscription042Test` | `webhookDeltaSignsUpsertsDeletesAndTracksFailureStatus` |
| `UpdateRemoteTest` | `deletesBadHashRetriesOnceAndReportsIntegrityFailure` |

额外 1 项为 `ModelDownloadSourcesTest.cancellationDoesNotStartBackup`。负责人独立重跑 3 次全部通过，判定为偶发；接手排查时在基线和 A 线工作区单独运行也均通过，A 线复测日志见 `outputs/A-isolated-recheck.log`。未修改该用例或本地模型逻辑。

2026-11-11 负责人确认：保持 A 线范围，不修复上述既有失败，接受该测试结果并继续提交、推送和编包。本次不能标为“全量测试通过”；交付依据是新增 10 项与指定旧测试共 44 项全部通过，以及上述基线与偶发失败复核结果。

## 本机环境

接手时已有工具链，已核实：Homebrew `openjdk@21` 21.0.13.0.3；Android cmdline-tools 23.0、platforms android-36、build-tools 35.0.0 和 36.0.0；Gradle wrapper 8.14.2。

构建时用 `JAVA_HOME` 选择 JDK；SDK 路径仅位于 Git 已忽略的 `local.properties`，未修改 `gradle.properties`。

## 真机验收与限制

按任务要求未安装 APK、未使用 adb。需负责人在手机上确认：两种地址模式均能看到开关，重启后状态保持；对接 B 线服务后按句显示时间，并由已有声纹流程认人。本次没有连接 B 线服务或 NAS，客户端测试使用任务书响应和 MockWebServer。
