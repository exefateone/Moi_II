package com.iipauk.spider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpiderCapabilityCatalogTest {
    @Test
    fun unconfiguredRuntimeDoesNotPretendModelsOrExternalControlAreReady() {
        val flags = SpiderRuntimeCapabilityFlags(
            overlayPermissionGranted = false,
            overlayRequestedEnabled = false,
            localEngineAvailable = false,
            localModelLoaded = false,
            providerConfigured = false,
            workspaceInventoryHandlerRegistered = true,
            openCodeZenConfigured = false
        )
        val skills = SpiderCapabilityCatalog.snapshot(flags).associateBy { it.id }
        assertEquals(SpiderCapabilityState.PERMISSION_REQUIRED, skills.getValue("floating_overlay").state)
        assertEquals(SpiderCapabilityState.SOURCE_UNVERIFIED, skills.getValue("local_llm").state)
        assertEquals(SpiderCapabilityState.CONFIGURATION_REQUIRED, skills.getValue("yandex_cloud").state)
        assertEquals(SpiderCapabilityState.READY, skills.getValue("workspace_inventory").state)
        assertEquals(SpiderCapabilityState.CONFIGURATION_REQUIRED, skills.getValue("opencode_zen_api").state)
        assertEquals(SpiderCapabilityState.NOT_CONNECTED, skills.getValue("external_app_control").state)
        assertEquals(SpiderCapabilityState.NOT_CONNECTED, skills.getValue("agent_execution").state)
    }

    @Test
    fun enabledRuntimeGuidesTheAssistantWithoutAddingToolsOrPermissionClaims() {
        val flags = SpiderRuntimeCapabilityFlags(
            overlayPermissionGranted = true,
            overlayRequestedEnabled = true,
            localEngineAvailable = true,
            localModelLoaded = true,
            providerConfigured = true,
            workspaceInventoryHandlerRegistered = true,
            openCodeZenConfigured = true
        )
        val skills = SpiderCapabilityCatalog.snapshot(flags).associateBy { it.id }
        val guide = SpiderCapabilityCatalog.systemPrompt(flags)
        assertEquals(SpiderCapabilityState.READY, skills.getValue("local_llm").state)
        assertEquals(SpiderCapabilityState.SOURCE_UNVERIFIED, skills.getValue("floating_overlay").state)
        assertEquals(SpiderCapabilityState.SOURCE_UNVERIFIED, skills.getValue("yandex_cloud").state)
        assertEquals(SpiderCapabilityState.SOURCE_UNVERIFIED, skills.getValue("opencode_zen_api").state)
        assertTrue(guide.contains("hosted API языковой модели"))
        assertTrue(guide.contains("локальные инструменты Agent Broker"))
        assertTrue(guide.contains("LIST_WORKSPACE_FILES"))
        assertTrue(guide.contains("не управляет внешними приложениями"))
        assertTrue(guide.contains("Самообучение означает локальную память"))
        assertFalse(guide.contains("EXECUTE_SHELL"))
    }
    @Test
    fun agentExecutionIsReadyOnlyWhenAllFiveRealWorkspaceHandlersExist() {
        val missingOne = SpiderRuntimeCapabilityFlags(
            overlayPermissionGranted = false,
            overlayRequestedEnabled = false,
            localEngineAvailable = false,
            localModelLoaded = false,
            providerConfigured = false,
            workspaceInventoryHandlerRegistered = true,
            workspaceReadHandlerRegistered = true,
            workspaceSearchHandlerRegistered = true,
            workspaceWriteHandlerRegistered = false,
            workspaceProjectCheckHandlerRegistered = false
        )
        assertEquals(SpiderCapabilityState.NOT_CONNECTED,
            SpiderCapabilityCatalog.snapshot(missingOne).associateBy { it.id }.getValue("agent_execution").state)
        assertTrue(SpiderCapabilityCatalog.systemPrompt(missingOne).contains("Набор Agent Broker неполон"))

        val almostAll = missingOne.copy(workspaceWriteHandlerRegistered = true)
        assertEquals(SpiderCapabilityState.NOT_CONNECTED,
            SpiderCapabilityCatalog.snapshot(almostAll).associateBy { it.id }.getValue("agent_execution").state)
        val all = almostAll.copy(workspaceProjectCheckHandlerRegistered = true)
        val agent = SpiderCapabilityCatalog.snapshot(all).associateBy { it.id }.getValue("agent_execution")
        assertEquals(SpiderCapabilityState.READY, agent.state)
        assertTrue(agent.description.contains("WRITE_WORKSPACE_FILE"))
        assertTrue(agent.description.contains("CHECK_WORKSPACE_PROJECT"))
        assertFalse(SpiderCapabilityCatalog.systemPrompt(all).contains("EXECUTE_SHELL"))
    }

}
