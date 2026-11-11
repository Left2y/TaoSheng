# Left2y/TaoSheng 分支说明

这是 [0xCoach/TaoSheng](https://github.com/0xCoach/TaoSheng) 的 fork。

## 分支

| 分支 | 用途 | 规则 |
| --- | --- | --- |
| `main` | 上游镜像 | 只跟上游同步，**不在上面提交** |
| `left2y` | 我们自用的版本 | = 上游 main + 我们的全部改动；编 apk 用这个 |
| `feat/*` | 单个功能 | 从 `upstream/main` 拉出，做完合进 `left2y`；通用的再向上游提 PR |

## 同步

定时任务自动做两件事：
1. `main` 快进到上游最新
2. 把 `main` 合进 `left2y`；有冲突就停下并提醒，不会强推

手动同步：`~/.config/alma/scripts/taosheng-fork-sync.sh`

## 我们的方向

- 自定义转写支持 `verbose_json`（句级时间戳 / 可选 speaker），让自定义模式也能按句认人
- 配套 NAS 上的 OpenAI 兼容转写服务（FunASR，独立仓库，不放这里）
