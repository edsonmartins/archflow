import {
    Stack, Text, TextInput, NumberInput, Switch, Select, Textarea, ScrollArea, Group, Badge, Divider,
} from '@mantine/core';
import { IconSettings } from '@tabler/icons-react';
import { useEditorStore } from '../stores/editor-store';

interface PropertyField {
    name: string;
    label: string;
    type: 'string' | 'number' | 'boolean' | 'select' | 'textarea';
    options?: { value: string; label: string }[];
    defaultValue?: unknown;
    description?: string;
    min?: number;
    max?: number;
    step?: number;
}

const NODE_PROPERTIES: Record<string, PropertyField[]> = {
    AGENT: [
        { name: 'model', label: 'Model', type: 'select', options: [
            { value: 'gpt-4', label: 'GPT-4' },
            { value: 'claude-3-opus', label: 'Claude 3 Opus' },
            { value: 'claude-3-sonnet', label: 'Claude 3 Sonnet' },
        ]},
        { name: 'temperature', label: 'Temperature', type: 'number', defaultValue: 0.7, min: 0, max: 2, step: 0.1 },
        { name: 'maxTokens', label: 'Max Tokens', type: 'number', defaultValue: 4096, min: 1, max: 128000 },
        { name: 'systemPrompt', label: 'System Prompt', type: 'textarea' },
    ],
    ASSISTANT: [
        { name: 'model', label: 'Model', type: 'select', options: [
            { value: 'gpt-4', label: 'GPT-4' },
            { value: 'claude-3-sonnet', label: 'Claude 3 Sonnet' },
        ]},
        { name: 'specialization', label: 'Specialization', type: 'string' },
        { name: 'temperature', label: 'Temperature', type: 'number', defaultValue: 0.7, min: 0, max: 2, step: 0.1 },
    ],
    LLM_CHAT: [
        { name: 'model', label: 'Model', type: 'select', options: [
            { value: 'gpt-4', label: 'GPT-4' },
            { value: 'gpt-4-turbo', label: 'GPT-4 Turbo' },
            { value: 'claude-3-opus', label: 'Claude 3 Opus' },
            { value: 'claude-3-sonnet', label: 'Claude 3 Sonnet' },
        ]},
        { name: 'temperature', label: 'Temperature', type: 'number', defaultValue: 0.7, min: 0, max: 2, step: 0.1 },
        { name: 'maxTokens', label: 'Max Tokens', type: 'number', defaultValue: 2048 },
        { name: 'stream', label: 'Streaming', type: 'boolean', defaultValue: false },
    ],
    TOOL: [
        { name: 'toolId', label: 'Tool ID', type: 'string' },
        { name: 'timeout', label: 'Timeout (ms)', type: 'number', defaultValue: 30000, min: 1000 },
        { name: 'retryOnError', label: 'Retry on Error', type: 'boolean', defaultValue: false },
    ],
    CONDITION: [
        { name: 'expression', label: 'Condition Expression', type: 'string', description: 'JavaScript expression' },
    ],
    PARALLEL: [
        { name: 'maxConcurrency', label: 'Max Concurrency', type: 'number', defaultValue: 3, min: 1, max: 10 },
        { name: 'waitAll', label: 'Wait for All', type: 'boolean', defaultValue: true },
    ],
};

export default function PropertyEditor() {
    const { selectedNode, updateSelectedNode, updateSelectedNodeConfig } = useEditorStore();

    if (!selectedNode) {
        return (
            <Stack align="center" justify="center" h="100%" gap="xs">
                <IconSettings size={32} color="gray" />
                <Text size="sm" c="dimmed">Select a node to edit properties</Text>
            </Stack>
        );
    }

    const fields = NODE_PROPERTIES[selectedNode.type] || [];
    const config = selectedNode.config || {};

    return (
        <Stack gap="xs" h="100%">
            <Group gap="xs">
                <Text fw={600} size="sm">Properties</Text>
                <Badge size="xs" variant="light">{selectedNode.type}</Badge>
            </Group>

            <Divider />

            <TextInput
                label="Node Label"
                size="xs"
                value={selectedNode.label}
                onChange={(e) => updateSelectedNode({ label: e.currentTarget.value })}
            />
            <TextInput label="Node ID" size="xs" value={selectedNode.id} readOnly c="dimmed" />

            {fields.length > 0 && (
                <>
                    <Divider label="Configuration" labelPosition="left" />
                    <ScrollArea style={{ flex: 1 }}>
                        <Stack gap="xs">
                            {fields.map((field) => (
                                <PropertyField
                                    key={field.name}
                                    field={field}
                                    value={config[field.name]}
                                    onChange={(value) => updateSelectedNodeConfig(field.name, value)}
                                />
                            ))}
                        </Stack>
                    </ScrollArea>
                </>
            )}
        </Stack>
    );
}

function PropertyField({
    field,
    value,
    onChange,
}: {
    field: PropertyField;
    value: unknown;
    onChange: (value: unknown) => void;
}) {
    switch (field.type) {
        case 'string':
            return (
                <TextInput
                    label={field.label}
                    description={field.description}
                    size="xs"
                    value={(value as string) ?? (field.defaultValue as string) ?? ''}
                    onChange={(e) => onChange(e.currentTarget.value)}
                />
            );
        case 'number':
            return (
                <NumberInput
                    label={field.label}
                    description={field.description}
                    size="xs"
                    value={(value as number) ?? (field.defaultValue as number)}
                    min={field.min}
                    max={field.max}
                    step={field.step}
                    onChange={onChange}
                />
            );
        case 'boolean':
            return (
                <Switch
                    label={field.label}
                    description={field.description}
                    size="xs"
                    checked={(value as boolean) ?? (field.defaultValue as boolean) ?? false}
                    onChange={(e) => onChange(e.currentTarget.checked)}
                />
            );
        case 'select':
            return (
                <Select
                    label={field.label}
                    description={field.description}
                    size="xs"
                    data={field.options || []}
                    value={(value as string) ?? (field.defaultValue as string) ?? null}
                    onChange={onChange}
                />
            );
        case 'textarea':
            return (
                <Textarea
                    label={field.label}
                    description={field.description}
                    size="xs"
                    autosize
                    minRows={3}
                    maxRows={8}
                    value={(value as string) ?? (field.defaultValue as string) ?? ''}
                    onChange={(e) => onChange(e.currentTarget.value)}
                />
            );
    }
}
