package io.github.golangsupport.view

import com.intellij.ide.projectView.ProjectViewNestingRulesProvider

/** go.sum under go.mod and go.work.sum under go.work in the Project view, as GoLand nests them (Project view | File Nesting). */
class GoFileNestingRules : ProjectViewNestingRulesProvider {
    override fun addFileNestingRules(consumer: ProjectViewNestingRulesProvider.Consumer) {
        consumer.addNestingRule("go.mod", "go.sum")
        consumer.addNestingRule("go.work", "go.work.sum")
    }
}
