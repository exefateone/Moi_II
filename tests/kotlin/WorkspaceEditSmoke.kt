import com.iipauk.spider.SpiderWorkspaceEditParser
import com.iipauk.spider.SpiderWorkspaceEditDiff
import org.json.JSONObject

fun runWorkspaceEditSmoke() {
    val valid = JSONObject()
        .put("status", "ok")
        .put("summary", "Добавлено описание")
        .put("updated_content", "# Demo\n\nDescription.\n")
        .toString()
    val proposal = SpiderWorkspaceEditParser.parse(valid)
    checkTrue(proposal.summary == "Добавлено описание", "edit proposal summary parsed")
    checkTrue(proposal.updatedContent == "# Demo\n\nDescription.\n", "full replacement body parsed")
    checkTrue(runCatching {
        SpiderWorkspaceEditParser.parse(JSONObject().put("status", "unsupported").put("summary", "Недостаточно контекста").toString())
    }.isFailure, "unsupported model edit never yields a writable proposal")
    checkTrue(runCatching {
        SpiderWorkspaceEditParser.parse(JSONObject().put("status", "ok").put("summary", "bad").put("updated_content", "").toString())
    }.isFailure, "empty model output is rejected")
    checkTrue(runCatching {
        SpiderWorkspaceEditParser.parse(JSONObject().put("status", "ok").put("summary", "bad").put("updated_content", "has\u0000binary").toString())
    }.isFailure, "NUL/binary-like proposal is rejected")
    checkTrue(runCatching {
        SpiderWorkspaceEditParser.parse(JSONObject().put("status", "ok").put("summary", "bad").put("updated_content", "has\u0007bell").toString())
    }.isFailure, "control characters are rejected from preview/write candidates")
    val diff = SpiderWorkspaceEditDiff.create("alpha\nold line\nomega", "alpha\nnew line\nomega")
    checkTrue(diff != null && diff.contains("- old line") && diff.contains("+ new line") && diff.contains("omega"), "line preview shows both sides and unchanged context")
    val oversizedDiff = SpiderWorkspaceEditDiff.create("old\n".repeat(35_000), "new\n".repeat(35_000))
    checkTrue(oversizedDiff == null, "oversized diff is refused instead of silently truncating the approval preview")
    println("PASS bounded AI edit proposal parser and complete diff preview: strict output, control-character guard and no truncated approval")
}
