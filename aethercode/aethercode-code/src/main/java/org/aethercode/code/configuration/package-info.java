/**
 * Ranked configuration provider chain for the {@code dcode} runtime.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.configuration} package. Provides:</p>
 * <ul>
 *   <li>{@link org.aethercode.code.configuration.Provider} — protocol every
 *       configuration source satisfies.</li>
 *   <li>{@link org.aethercode.code.configuration.ConfigTypes} — sealed
 *       {@code ProviderResult} (Found/Unset/Invalid), provider health, and
 *       snapshot types.</li>
 *   <li>{@link org.aethercode.code.configuration.ConfigPaths} — fixed
 *       managed-config path resolution per platform.</li>
 *   <li>{@link org.aethercode.code.configuration.ConfigProviders} — TOML and
 *       environment providers plus coercion helpers.</li>
 *   <li>{@link org.aethercode.code.configuration.ConfigResolver} — rank-based
 *       configuration resolution engine.</li>
 *   <li>{@link org.aethercode.code.configuration.ConfigService} — process
 *       snapshot management and managed-config health.</li>
 *   <li>{@link org.aethercode.code.configuration.ConfigWriter} — atomic
 *       user-config writes.</li>
 *   <li>{@link org.aethercode.code.configuration.ThemeResolution} — theme
 *       preference resolution shared by the config chain and the UI.</li>
 * </ul>
 */
package org.aethercode.code.configuration;
