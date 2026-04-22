import {
    Paper, TextInput, PasswordInput, Button, Stack, Text, Alert, Center, Box, Code,
} from '@mantine/core';
import { IconLock, IconAlertCircle, IconInfoCircle } from '@tabler/icons-react';
import { useState, FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../stores/auth-store';

function ArchflowLogo() {
    return (
        <svg width="48" height="48" viewBox="0 0 48 48" fill="none">
            <rect width="48" height="48" rx="12" fill="#185FA5"/>
            <circle cx="14" cy="24" r="5" stroke="white" strokeWidth="2"/>
            <circle cx="34" cy="14" r="4" stroke="white" strokeWidth="2"/>
            <circle cx="34" cy="34" r="4" stroke="white" strokeWidth="2"/>
            <line x1="19" y1="22" x2="30" y2="15" stroke="white" strokeWidth="2" strokeLinecap="round"/>
            <line x1="19" y1="26" x2="30" y2="33" stroke="white" strokeWidth="2" strokeLinecap="round"/>
        </svg>
    );
}

export default function LoginPage() {
    const navigate = useNavigate();
    const { login, loading, error, clearError } = useAuthStore();
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');

    const handleSubmit = async (e: FormEvent) => {
        e.preventDefault();
        try {
            await login(username, password);
            navigate('/');
        } catch {
            // error is set in store
        }
    };

    return (
        <Center h="100vh" style={{ background: 'var(--color-background-tertiary)' }}>
            <Box w={400}>
                <Stack align="center" mb="xl" gap="xs">
                    <ArchflowLogo />
                    <Text fw={700} size="xl" mt="xs">Archflow</Text>
                    <Text c="dimmed" size="sm">Agent orchestration platform</Text>
                </Stack>

                <Paper withBorder shadow="md" p="xl" radius="lg">
                    <form onSubmit={handleSubmit}>
                        <Stack gap="md">
                            {error && (
                                <Alert
                                    icon={<IconAlertCircle size={16} />}
                                    color="red"
                                    withCloseButton
                                    onClose={clearError}
                                >
                                    {error}
                                </Alert>
                            )}

                            <TextInput
                                label="Username"
                                placeholder="your-username"
                                required
                                value={username}
                                onChange={(e) => setUsername(e.currentTarget.value)}
                                autoFocus
                            />

                            <PasswordInput
                                label="Password"
                                placeholder="your-password"
                                required
                                value={password}
                                onChange={(e) => setPassword(e.currentTarget.value)}
                            />

                            <Button
                                type="submit"
                                fullWidth
                                loading={loading}
                                leftSection={<IconLock size={16} />}
                            >
                                Sign in
                            </Button>
                        </Stack>
                    </form>
                </Paper>

                <Alert
                    icon={<IconInfoCircle size={16} />}
                    color="blue"
                    variant="light"
                    mt="md"
                    radius="lg"
                    title="Dev credentials"
                >
                    <Text size="sm">
                        Username: <Code>admin</Code> &nbsp; Password: <Code>admin123</Code>
                    </Text>
                </Alert>
            </Box>
        </Center>
    );
}
