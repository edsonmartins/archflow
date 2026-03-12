import {
    Paper, TextInput, PasswordInput, Button, Stack, Text, Alert, Center, Box,
} from '@mantine/core';
import { IconLock, IconAlertCircle } from '@tabler/icons-react';
import { useState, FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../stores/auth-store';

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
        <Center h="100vh" bg="#f8fafc">
            <Box w={400}>
                <Stack align="center" mb="lg">
                    <Text fw={700} size="xl">Archflow</Text>
                    <Text c="dimmed" size="sm">Sign in to your account</Text>
                </Stack>

                <Paper withBorder shadow="md" p="xl" radius="md">
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
            </Box>
        </Center>
    );
}
