package dev.remotegpt.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.remotegpt.app.ui.TimelineCard
import dev.remotegpt.client.TimelineItem

@Composable internal fun MathGallery() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            TimelineCard(TimelineItem("math", "preview", "agentMessage", MATH_SAMPLE, true), {})
        }
    }
}

private val MATH_SAMPLE = """
## 数学公式 · Mathematics
行内公式 ${'$'}E = mc^2${'$'} 与中文自然混排，分数 \(\frac{a}{b}\) 保持清晰。

${'$'}${'$'}
\int_0^1 x^2\,dx = \frac{1}{3}
${'$'}${'$'}

\[
A = \begin{pmatrix} a & b \\ c & d \end{pmatrix},\quad
\sum_{i=1}^{n} i = \frac{n(n+1)}{2}
\]

- 正态分布：${'$'}p(x)=\frac{1}{\sqrt{2\pi}}e^{-x^2/2}${'$'}
- **勾股定理**：${'$'}a^2+b^2=c^2${'$'}

| 名称 | 公式 |
| --- | --- |
| 欧拉公式 | ${'$'}e^{i\pi}+1=0${'$'} |
| 绝对值 | ${'$'}|x|${'$'} |

代码中的 `${'$'}x^2${'$'}` 保留原样；价格 ${'$'}5 和 ${'$'}10 不应变成公式。

\[x_1+x_2+x_3+x_4+x_5+x_6+x_7+x_8+x_9+x_{10}+x_{11}+x_{12}=0\]

语法错误保留源码：\(\notARealCommand{x}\)。
""".trimIndent()
