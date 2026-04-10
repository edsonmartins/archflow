-- Redis migration script: archflow:chat:<conversationId> → archflow:chat:SYSTEM:<conversationId>
--
-- Run with: redis-cli --eval migrate_chat_keys.lua
--
-- This script migrates existing chat memory keys from the legacy format
-- (without tenant prefix) to the new multi-tenant format.
-- Legacy keys are renamed, not deleted, to ensure zero data loss.
--
-- Usage:
--   redis-cli --eval migrate_chat_keys.lua
-- Or from application:
--   jedis.eval(script)

local cursor = "0"
local prefix = "archflow:chat:"
local newPrefix = "archflow:chat:SYSTEM:"
local migrated = 0

repeat
    local result = redis.call("SCAN", cursor, "MATCH", prefix .. "*", "COUNT", 100)
    cursor = result[1]
    local keys = result[2]

    for _, key in ipairs(keys) do
        -- Skip keys already in new format (contain 2+ colons after prefix)
        local suffix = string.sub(key, #prefix + 1)
        if not string.find(suffix, ":") then
            -- Legacy format: archflow:chat:<conversationId>
            -- New format: archflow:chat:SYSTEM:<conversationId>
            local newKey = newPrefix .. suffix
            if redis.call("EXISTS", newKey) == 0 then
                redis.call("RENAME", key, newKey)
                migrated = migrated + 1
            end
        end
    end
until cursor == "0"

return migrated
