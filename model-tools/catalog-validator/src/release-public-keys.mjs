/**
 * Public Ed25519 keys trusted by the Android client and release freezer.
 * Private signing keys live outside the repository.
 */
export const BUILTIN_CATALOG_PUBLIC_KEYS = Object.freeze({
  'catalog-key-2026-c': "-----BEGIN PUBLIC KEY-----\nMCowBQYDK2VwAyEAniPovqLlpcicIfCCj9JkKmMAWcgZy0ZbHKoFyDujcZw=\n-----END PUBLIC KEY-----\n",
  'catalog-key-2026-a': '-----BEGIN PUBLIC KEY-----\nMCowBQYDK2VwAyEAUtA1YMoUNVD4Oopro6sEp61YDtMQXu5rIqGZZi6BlDQ=\n-----END PUBLIC KEY-----\n',
  'catalog-key-2026-b': '-----BEGIN PUBLIC KEY-----\nMCowBQYDK2VwAyEA5IJ4t5JTAIde8oIbso2R6OB5Y3qGrvbqGiKjXRXuim8=\n-----END PUBLIC KEY-----\n',
});

export const BUILTIN_MANIFEST_PUBLIC_KEYS = Object.freeze({
  'manifest-key-2026-c': "-----BEGIN PUBLIC KEY-----\nMCowBQYDK2VwAyEAHhm34mEyIsUcU6UYLIjALtWrlV7FPxa8MIQUrLlxeiQ=\n-----END PUBLIC KEY-----\n",
  'manifest-key-2026-a': '-----BEGIN PUBLIC KEY-----\nMCowBQYDK2VwAyEAG1WvR2G5iRbzIdr6UOqh8tZ7cvJ/AaTvDyRQfZ8Yg0w=\n-----END PUBLIC KEY-----\n',
  'manifest-key-2026-b': '-----BEGIN PUBLIC KEY-----\nMCowBQYDK2VwAyEAHZ/m6+/9kdbW/Xlvw8FIPukKz8EY+xmKl8p7vkVwLfY=\n-----END PUBLIC KEY-----\n',
});
