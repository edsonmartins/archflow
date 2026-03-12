package br.com.archflow.observability.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AuditAction")
class AuditActionTest {

    @Nested
    @DisplayName("Enum values")
    class EnumValues {

        @Test
        @DisplayName("should contain all expected CRUD actions")
        void shouldContainAllCrudActions() {
            assertThat(AuditAction.values()).contains(
                    AuditAction.CREATE,
                    AuditAction.READ,
                    AuditAction.UPDATE,
                    AuditAction.DELETE
            );
        }

        @Test
        @DisplayName("should contain all expected execution actions")
        void shouldContainAllExecutionActions() {
            assertThat(AuditAction.values()).contains(
                    AuditAction.WORKFLOW_EXECUTE,
                    AuditAction.WORKFLOW_COMPLETED,
                    AuditAction.WORKFLOW_FAILED,
                    AuditAction.AGENT_EXECUTE,
                    AuditAction.AGENT_COMPLETED,
                    AuditAction.TOOL_INVOKE,
                    AuditAction.TOOL_COMPLETED,
                    AuditAction.LLM_REQUEST,
                    AuditAction.LLM_COMPLETED
            );
        }

        @Test
        @DisplayName("should contain all expected authentication actions")
        void shouldContainAllAuthenticationActions() {
            assertThat(AuditAction.values()).contains(
                    AuditAction.LOGIN_SUCCESS,
                    AuditAction.LOGIN_FAILED,
                    AuditAction.LOGOUT,
                    AuditAction.TOKEN_REFRESH
            );
        }

        @Test
        @DisplayName("should contain all expected authorization actions")
        void shouldContainAllAuthorizationActions() {
            assertThat(AuditAction.values()).contains(
                    AuditAction.PERMISSION_GRANTED,
                    AuditAction.PERMISSION_REVOKED,
                    AuditAction.ROLE_ASSIGNED,
                    AuditAction.ROLE_REMOVED
            );
        }

        @Test
        @DisplayName("should contain all expected data operations")
        void shouldContainAllDataOperations() {
            assertThat(AuditAction.values()).contains(
                    AuditAction.DATA_EXPORT,
                    AuditAction.DATA_IMPORT,
                    AuditAction.DATA_BULK_OPERATION
            );
        }

        @Test
        @DisplayName("should contain all expected configuration actions")
        void shouldContainAllConfigurationActions() {
            assertThat(AuditAction.values()).contains(
                    AuditAction.CONFIG_CHANGE,
                    AuditAction.SETTING_CHANGE
            );
        }

        @Test
        @DisplayName("should contain all expected security actions")
        void shouldContainAllSecurityActions() {
            assertThat(AuditAction.values()).contains(
                    AuditAction.SECURITY_EVENT,
                    AuditAction.SUSPICIOUS_ACTIVITY,
                    AuditAction.ACCESS_DENIED
            );
        }

        @Test
        @DisplayName("should contain all expected admin actions")
        void shouldContainAllAdminActions() {
            assertThat(AuditAction.values()).contains(
                    AuditAction.ADMIN_ACTION,
                    AuditAction.MAINTENANCE_START,
                    AuditAction.MAINTENANCE_END
            );
        }

        @Test
        @DisplayName("should have exactly 30 enum values")
        void shouldHaveExpectedCount() {
            assertThat(AuditAction.values()).hasSize(30);
        }
    }

    @Nested
    @DisplayName("getCode()")
    class GetCode {

        @Test
        @DisplayName("should return correct code for CRUD actions")
        void shouldReturnCorrectCodeForCrudActions() {
            assertThat(AuditAction.CREATE.getCode()).isEqualTo("create");
            assertThat(AuditAction.READ.getCode()).isEqualTo("read");
            assertThat(AuditAction.UPDATE.getCode()).isEqualTo("update");
            assertThat(AuditAction.DELETE.getCode()).isEqualTo("delete");
        }

        @Test
        @DisplayName("should return correct code for execution actions")
        void shouldReturnCorrectCodeForExecutionActions() {
            assertThat(AuditAction.WORKFLOW_EXECUTE.getCode()).isEqualTo("workflow.execute");
            assertThat(AuditAction.AGENT_EXECUTE.getCode()).isEqualTo("agent.execute");
            assertThat(AuditAction.TOOL_INVOKE.getCode()).isEqualTo("tool.invoke");
            assertThat(AuditAction.LLM_REQUEST.getCode()).isEqualTo("llm.request");
        }

        @ParameterizedTest
        @EnumSource(AuditAction.class)
        @DisplayName("should return non-null code for all actions")
        void shouldReturnNonNullCodeForAllActions(AuditAction action) {
            assertThat(action.getCode()).isNotNull().isNotEmpty();
        }
    }

    @Nested
    @DisplayName("fromCode()")
    class FromCode {

        @Test
        @DisplayName("should find action by valid code")
        void shouldFindActionByValidCode() {
            assertThat(AuditAction.fromCode("create")).isEqualTo(AuditAction.CREATE);
            assertThat(AuditAction.fromCode("workflow.execute")).isEqualTo(AuditAction.WORKFLOW_EXECUTE);
            assertThat(AuditAction.fromCode("login.success")).isEqualTo(AuditAction.LOGIN_SUCCESS);
        }

        @Test
        @DisplayName("should return null for unknown code")
        void shouldReturnNullForUnknownCode() {
            assertThat(AuditAction.fromCode("unknown.action")).isNull();
        }

        @Test
        @DisplayName("should return null for null code")
        void shouldReturnNullForNullCode() {
            assertThat(AuditAction.fromCode(null)).isNull();
        }

        @ParameterizedTest
        @EnumSource(AuditAction.class)
        @DisplayName("should round-trip through code for all actions")
        void shouldRoundTripThroughCode(AuditAction action) {
            // Arrange
            String code = action.getCode();

            // Act
            AuditAction result = AuditAction.fromCode(code);

            // Assert
            assertThat(result).isEqualTo(action);
        }
    }

    @Nested
    @DisplayName("isCrudOperation()")
    class IsCrudOperation {

        @Test
        @DisplayName("should return true for CRUD actions")
        void shouldReturnTrueForCrudActions() {
            assertThat(AuditAction.CREATE.isCrudOperation()).isTrue();
            assertThat(AuditAction.READ.isCrudOperation()).isTrue();
            assertThat(AuditAction.UPDATE.isCrudOperation()).isTrue();
            assertThat(AuditAction.DELETE.isCrudOperation()).isTrue();
        }

        @Test
        @DisplayName("should return false for non-CRUD actions")
        void shouldReturnFalseForNonCrudActions() {
            assertThat(AuditAction.WORKFLOW_EXECUTE.isCrudOperation()).isFalse();
            assertThat(AuditAction.LOGIN_SUCCESS.isCrudOperation()).isFalse();
            assertThat(AuditAction.SECURITY_EVENT.isCrudOperation()).isFalse();
            assertThat(AuditAction.CONFIG_CHANGE.isCrudOperation()).isFalse();
        }
    }

    @Nested
    @DisplayName("isExecutionOperation()")
    class IsExecutionOperation {

        @Test
        @DisplayName("should return true for workflow actions")
        void shouldReturnTrueForWorkflowActions() {
            assertThat(AuditAction.WORKFLOW_EXECUTE.isExecutionOperation()).isTrue();
            assertThat(AuditAction.WORKFLOW_COMPLETED.isExecutionOperation()).isTrue();
            assertThat(AuditAction.WORKFLOW_FAILED.isExecutionOperation()).isTrue();
        }

        @Test
        @DisplayName("should return true for agent actions")
        void shouldReturnTrueForAgentActions() {
            assertThat(AuditAction.AGENT_EXECUTE.isExecutionOperation()).isTrue();
            assertThat(AuditAction.AGENT_COMPLETED.isExecutionOperation()).isTrue();
        }

        @Test
        @DisplayName("should return true for tool actions")
        void shouldReturnTrueForToolActions() {
            assertThat(AuditAction.TOOL_INVOKE.isExecutionOperation()).isTrue();
            assertThat(AuditAction.TOOL_COMPLETED.isExecutionOperation()).isTrue();
        }

        @Test
        @DisplayName("should return true for LLM actions")
        void shouldReturnTrueForLlmActions() {
            assertThat(AuditAction.LLM_REQUEST.isExecutionOperation()).isTrue();
            assertThat(AuditAction.LLM_COMPLETED.isExecutionOperation()).isTrue();
        }

        @Test
        @DisplayName("should return false for non-execution actions")
        void shouldReturnFalseForNonExecutionActions() {
            assertThat(AuditAction.CREATE.isExecutionOperation()).isFalse();
            assertThat(AuditAction.LOGIN_SUCCESS.isExecutionOperation()).isFalse();
            assertThat(AuditAction.CONFIG_CHANGE.isExecutionOperation()).isFalse();
        }
    }

    @Nested
    @DisplayName("isAuthOperation()")
    class IsAuthOperation {

        @Test
        @DisplayName("should return true for authentication actions")
        void shouldReturnTrueForAuthenticationActions() {
            assertThat(AuditAction.LOGIN_SUCCESS.isAuthOperation()).isTrue();
            assertThat(AuditAction.LOGIN_FAILED.isAuthOperation()).isTrue();
            assertThat(AuditAction.LOGOUT.isAuthOperation()).isTrue();
            assertThat(AuditAction.TOKEN_REFRESH.isAuthOperation()).isTrue();
        }

        @Test
        @DisplayName("should return true for authorization actions")
        void shouldReturnTrueForAuthorizationActions() {
            assertThat(AuditAction.PERMISSION_GRANTED.isAuthOperation()).isTrue();
            assertThat(AuditAction.PERMISSION_REVOKED.isAuthOperation()).isTrue();
            assertThat(AuditAction.ROLE_ASSIGNED.isAuthOperation()).isTrue();
            assertThat(AuditAction.ROLE_REMOVED.isAuthOperation()).isTrue();
        }

        @Test
        @DisplayName("should return false for non-auth actions")
        void shouldReturnFalseForNonAuthActions() {
            assertThat(AuditAction.CREATE.isAuthOperation()).isFalse();
            assertThat(AuditAction.WORKFLOW_EXECUTE.isAuthOperation()).isFalse();
            assertThat(AuditAction.SECURITY_EVENT.isAuthOperation()).isFalse();
        }
    }

    @Nested
    @DisplayName("isSecurityOperation()")
    class IsSecurityOperation {

        @Test
        @DisplayName("should return true for security actions")
        void shouldReturnTrueForSecurityActions() {
            assertThat(AuditAction.SECURITY_EVENT.isSecurityOperation()).isTrue();
            assertThat(AuditAction.SUSPICIOUS_ACTIVITY.isSecurityOperation()).isTrue();
            assertThat(AuditAction.ACCESS_DENIED.isSecurityOperation()).isTrue();
        }

        @Test
        @DisplayName("should return false for non-security actions")
        void shouldReturnFalseForNonSecurityActions() {
            assertThat(AuditAction.CREATE.isSecurityOperation()).isFalse();
            assertThat(AuditAction.LOGIN_SUCCESS.isSecurityOperation()).isFalse();
            assertThat(AuditAction.WORKFLOW_EXECUTE.isSecurityOperation()).isFalse();
        }
    }
}
