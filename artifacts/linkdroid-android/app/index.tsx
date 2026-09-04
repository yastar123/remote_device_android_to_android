import AsyncStorage from '@react-native-async-storage/async-storage';
import { Feather } from '@expo/vector-icons';
import * as Haptics from 'expo-haptics';
import React, { useEffect, useMemo, useState } from 'react';
import {
  ActivityIndicator,
  Keyboard,
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { KeyboardAwareScrollViewCompat } from '@/components/KeyboardAwareScrollViewCompat';
import { useColors } from '@/hooks/useColors';

type AppView = 'login' | 'home' | 'devices' | 'profile' | 'session';
type Notice = { tone: 'success' | 'error'; message: string } | null;

type SavedDevice = {
  id: string;
  name: string;
  model: string;
  status: 'Online' | 'Offline';
  lastSeen: string;
};

const DEVICE_ID = '884 512 307';
const INITIAL_DEVICES: SavedDevice[] = [
  { id: '884 512 307', name: 'Samsung A54', model: 'Android 14', status: 'Online', lastSeen: 'Perangkat ini' },
  { id: '221 095 648', name: 'Xiaomi Pad 6', model: 'Android 13', status: 'Offline', lastSeen: '2 jam lalu' },
  { id: '731 440 219', name: 'Galaxy S23', model: 'Android 14', status: 'Online', lastSeen: 'Aktif sekarang' },
];

function IconButton({
  name,
  onPress,
  color,
  accessibilityLabel,
  size = 20,
}: {
  name: React.ComponentProps<typeof Feather>['name'];
  onPress?: () => void;
  color: string;
  accessibilityLabel: string;
  size?: number;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel}
      onPress={onPress}
      style={({ pressed }) => [styles.iconButton, pressed && styles.pressed]}
    >
      <Feather name={name} size={size} color={color} />
    </Pressable>
  );
}

function BrandMark({ colors, small = false }: { colors: ReturnType<typeof useColors>; small?: boolean }) {
  return (
    <View style={[styles.brandMark, small && styles.brandMarkSmall, { backgroundColor: colors.primary }]}>
      <Feather name="link-2" color={colors.primaryForeground} size={small ? 18 : 28} strokeWidth={2.2} />
    </View>
  );
}

function PrimaryButton({
  label,
  onPress,
  colors,
  disabled = false,
  icon,
}: {
  label: string;
  onPress: () => void;
  colors: ReturnType<typeof useColors>;
  disabled?: boolean;
  icon?: React.ComponentProps<typeof Feather>['name'];
}) {
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      disabled={disabled}
      style={({ pressed }) => [
        styles.primaryButton,
        { backgroundColor: disabled ? colors.border : colors.primary },
        pressed && !disabled && styles.pressed,
      ]}
    >
      {icon ? <Feather name={icon} size={17} color={colors.primaryForeground} /> : null}
      <Text style={[styles.primaryButtonText, { color: disabled ? colors.mutedForeground : colors.primaryForeground }]}>
        {label}
      </Text>
    </Pressable>
  );
}

function NoticeBanner({ notice, colors, onDismiss }: { notice: Notice; colors: ReturnType<typeof useColors>; onDismiss: () => void }) {
  if (!notice) return null;
  const isError = notice.tone === 'error';
  return (
    <Pressable
      accessibilityRole="alert"
      onPress={onDismiss}
      style={[
        styles.notice,
        { backgroundColor: isError ? '#FFF0F2' : '#EAF8F1', borderColor: isError ? '#F3CDD3' : '#C6EBD6' },
      ]}
    >
      <Feather name={isError ? 'alert-circle' : 'check-circle'} size={17} color={isError ? colors.destructive : '#2B9B65'} />
      <Text style={[styles.noticeText, { color: isError ? colors.destructive : '#23794F' }]}>{notice.message}</Text>
      <Feather name="x" size={15} color={isError ? colors.destructive : '#23794F'} />
    </Pressable>
  );
}

function LoginView({
  colors,
  insets,
  onLogin,
}: {
  colors: ReturnType<typeof useColors>;
  insets: { top: number; bottom: number };
  onLogin: (email: string) => void;
}) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [isRegister, setIsRegister] = useState(false);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const submit = async () => {
    Keyboard.dismiss();
    if (!email.includes('@') || password.length < 6) {
      setError('Masukkan email yang valid dan kata sandi minimal 6 karakter.');
      return;
    }
    setError('');
    setLoading(true);
    await new Promise((resolve) => setTimeout(resolve, 550));
    await onLogin(email);
    setLoading(false);
  };

  const useDemo = async () => {
    setEmail('demo@linkdroid.app');
    setPassword('linkdroid');
    setError('');
    setLoading(true);
    await new Promise((resolve) => setTimeout(resolve, 350));
    await onLogin('demo@linkdroid.app');
    setLoading(false);
  };

  return (
    <KeyboardAwareScrollViewCompat
      contentContainerStyle={[styles.loginContent, { paddingTop: insets.top + 34, paddingBottom: insets.bottom + 28 }]}
      showsVerticalScrollIndicator={false}
      bottomOffset={24}
    >
      <View style={styles.loginBrand}>
        <BrandMark colors={colors} />
        <Text style={[styles.brandName, { color: colors.foreground }]}>LinkDroid</Text>
        <Text style={[styles.brandTagline, { color: colors.mutedForeground }]}>Remote support, made simple.</Text>
      </View>

      <View style={[styles.loginCard, { backgroundColor: colors.card, borderColor: colors.border }]}>
        <Text style={[styles.loginTitle, { color: colors.foreground }]}>{isRegister ? 'Buat akun baru' : 'Selamat datang kembali'}</Text>
        <Text style={[styles.loginSubtitle, { color: colors.mutedForeground }]}>
          {isRegister ? 'Mulai terhubung ke perangkat Android Anda.' : 'Masuk untuk mengakses perangkat Anda.'}
        </Text>

        <View style={styles.formGap}>
          <Text style={[styles.fieldLabel, { color: colors.foreground }]}>Email</Text>
          <View style={[styles.inputWrap, { backgroundColor: colors.background, borderColor: error ? colors.destructive : colors.input }]}>
            <Feather name="mail" size={18} color={colors.mutedForeground} />
            <TextInput
              testID="email-input"
              accessibilityLabel="Email"
              autoCapitalize="none"
              autoCorrect={false}
              keyboardType="email-address"
              placeholder="nama@email.com"
              placeholderTextColor={colors.mutedForeground}
              style={[styles.textInput, { color: colors.foreground }]}
              value={email}
              onChangeText={setEmail}
            />
          </View>

          <Text style={[styles.fieldLabel, { color: colors.foreground }]}>Kata sandi</Text>
          <View style={[styles.inputWrap, { backgroundColor: colors.background, borderColor: error ? colors.destructive : colors.input }]}>
            <Feather name="lock" size={18} color={colors.mutedForeground} />
            <TextInput
              testID="password-input"
              accessibilityLabel="Kata sandi"
              autoCapitalize="none"
              secureTextEntry={!showPassword}
              placeholder="Minimal 6 karakter"
              placeholderTextColor={colors.mutedForeground}
              style={[styles.textInput, { color: colors.foreground }]}
              value={password}
              onChangeText={setPassword}
            />
            <IconButton
              name={showPassword ? 'eye-off' : 'eye'}
              color={colors.mutedForeground}
              accessibilityLabel={showPassword ? 'Sembunyikan kata sandi' : 'Tampilkan kata sandi'}
              onPress={() => setShowPassword((value) => !value)}
              size={18}
            />
          </View>
        </View>

        {error ? <Text style={[styles.errorText, { color: colors.destructive }]}>{error}</Text> : null}

        <PrimaryButton
          label={loading ? 'Menyiapkan...' : isRegister ? 'Buat akun' : 'Masuk'}
          onPress={() => void submit()}
          colors={colors}
          disabled={loading}
        />
        {loading ? <ActivityIndicator color={colors.primary} style={styles.loadingIndicator} /> : null}

        <Pressable accessibilityRole="button" onPress={useDemo} style={({ pressed }) => [styles.demoButton, pressed && styles.pressed]}>
          <Feather name="play-circle" size={16} color={colors.primary} />
          <Text style={[styles.demoButtonText, { color: colors.primary }]}>Coba demo tanpa akun</Text>
        </Pressable>
      </View>

      <View style={styles.loginFooter}>
        <Text style={[styles.footerText, { color: colors.mutedForeground }]}>
          {isRegister ? 'Sudah punya akun?' : 'Belum punya akun?'}
        </Text>
        <Pressable onPress={() => { setIsRegister((value) => !value); setError(''); }}>
          <Text style={[styles.footerLink, { color: colors.primary }]}>{isRegister ? 'Masuk' : 'Daftar sekarang'}</Text>
        </Pressable>
      </View>
      <Text style={[styles.versionText, { color: colors.mutedForeground }]}>LinkDroid v1.0 · Koneksi terenkripsi</Text>
    </KeyboardAwareScrollViewCompat>
  );
}

function Header({
  colors,
  title,
  subtitle,
  onSettings,
}: {
  colors: ReturnType<typeof useColors>;
  title: string;
  subtitle?: string;
  onSettings?: () => void;
}) {
  return (
    <View style={styles.header}>
      <View>
        {subtitle ? <Text style={[styles.eyebrow, { color: colors.primary }]}>{subtitle}</Text> : null}
        <Text style={[styles.screenTitle, { color: colors.foreground }]}>{title}</Text>
      </View>
      {onSettings ? (
        <IconButton name="settings" color={colors.foreground} accessibilityLabel="Buka pengaturan" onPress={onSettings} />
      ) : null}
    </View>
  );
}

function DeviceIdCard({ colors, onCopy }: { colors: ReturnType<typeof useColors>; onCopy: () => void }) {
  return (
    <View style={[styles.deviceIdCard, { backgroundColor: colors.primary }]}>
      <View style={styles.deviceIdGlow} />
      <View style={styles.deviceIdTop}>
        <View style={styles.deviceIdLabelRow}>
          <View style={styles.liveDot} />
          <Text style={styles.deviceIdLabel}>ID PERANGKAT ANDA</Text>
        </View>
        <Feather name="shield" size={18} color="rgba(255,255,255,0.72)" />
      </View>
      <Text style={styles.deviceId}>{DEVICE_ID}</Text>
      <View style={styles.deviceIdBottom}>
        <Text style={styles.deviceIdHint}>Bagikan ID ini untuk menerima bantuan</Text>
        <Pressable accessibilityRole="button" accessibilityLabel="Salin ID perangkat" onPress={onCopy} style={({ pressed }) => [styles.copyButton, pressed && styles.pressed]}>
          <Feather name="copy" size={15} color={colors.primary} />
          <Text style={[styles.copyButtonText, { color: colors.primary }]}>Salin</Text>
        </Pressable>
      </View>
    </View>
  );
}

function HomeView({
  colors,
  email,
  devices,
  onConnect,
  onCopy,
  onOpenSettings,
  onDevice,
}: {
  colors: ReturnType<typeof useColors>;
  email: string;
  devices: SavedDevice[];
  onConnect: (id: string) => void;
  onCopy: () => void;
  onOpenSettings: () => void;
  onDevice: (device: SavedDevice) => void;
}) {
  const [targetId, setTargetId] = useState('');
  const [error, setError] = useState('');
  const firstName = email.split('@')[0].split(/[._-]/)[0];
  const onlineDevices = devices.filter((device) => device.status === 'Online' && device.id !== DEVICE_ID);

  const connect = () => {
    const normalized = targetId.replace(/\s/g, '');
    if (normalized.length < 9) {
      setError('Masukkan 9 digit ID perangkat tujuan.');
      return;
    }
    setError('');
    onConnect(targetId);
  };

  return (
    <ScrollView contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
      <Header colors={colors} title={`Halo, ${firstName || 'teman'}`} subtitle="SELAMAT DATANG KEMBALI" onSettings={onOpenSettings} />
      <NoticeBanner notice={null} colors={colors} onDismiss={() => undefined} />
      <DeviceIdCard colors={colors} onCopy={onCopy} />

      <View style={styles.sectionHeader}>
        <View>
          <Text style={[styles.sectionTitle, { color: colors.foreground }]}>Hubungkan ke perangkat</Text>
          <Text style={[styles.sectionSubtitle, { color: colors.mutedForeground }]}>Masukkan ID Android yang ingin Anda bantu</Text>
        </View>
        <View style={[styles.secureBadge, { backgroundColor: colors.secondary }]}>
          <Feather name="lock" size={12} color={colors.primary} />
          <Text style={[styles.secureBadgeText, { color: colors.primary }]}>Aman</Text>
        </View>
      </View>

      <View style={[styles.connectCard, { backgroundColor: colors.card, borderColor: colors.border }]}>
        <Text style={[styles.fieldLabel, { color: colors.foreground }]}>ID perangkat tujuan</Text>
        <View style={[styles.connectInputWrap, { backgroundColor: colors.background, borderColor: error ? colors.destructive : colors.input }]}>
          <Feather name="smartphone" size={19} color={colors.primary} />
          <TextInput
            testID="target-device-input"
            accessibilityLabel="ID perangkat tujuan"
            keyboardType="number-pad"
            placeholder="000 000 000"
            placeholderTextColor={colors.mutedForeground}
            style={[styles.connectInput, { color: colors.foreground }]}
            value={targetId}
            maxLength={11}
            onChangeText={(value) => {
              setTargetId(value);
              if (error) setError('');
            }}
          />
          {targetId ? <IconButton name="x-circle" color={colors.mutedForeground} accessibilityLabel="Hapus ID perangkat" onPress={() => setTargetId('')} size={17} /> : null}
        </View>
        {error ? <Text style={[styles.errorText, { color: colors.destructive }]}>{error}</Text> : null}
        <PrimaryButton label="Hubungkan sekarang" onPress={connect} colors={colors} icon="arrow-up-right" disabled={!targetId} />
      </View>

      <View style={styles.sectionHeader}>
        <View>
          <Text style={[styles.sectionTitle, { color: colors.foreground }]}>Perangkat online</Text>
          <Text style={[styles.sectionSubtitle, { color: colors.mutedForeground }]}>Terhubung dan siap digunakan</Text>
        </View>
        <Text style={[styles.viewAll, { color: colors.primary }]}>{onlineDevices.length} aktif</Text>
      </View>

      {onlineDevices.length ? (
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.deviceChips}>
          {onlineDevices.map((device) => (
            <Pressable
              key={device.id}
              accessibilityRole="button"
              onPress={() => onDevice(device)}
              style={({ pressed }) => [styles.deviceChip, { backgroundColor: colors.card, borderColor: colors.border }, pressed && styles.pressed]}
            >
              <View style={[styles.deviceChipIcon, { backgroundColor: colors.secondary }]}>
                <Feather name="smartphone" size={16} color={colors.primary} />
              </View>
              <View style={styles.flexOne}>
                <Text style={[styles.deviceChipName, { color: colors.foreground }]} numberOfLines={1}>{device.name}</Text>
                <Text style={[styles.deviceChipId, { color: colors.mutedForeground }]}>{device.id}</Text>
              </View>
              <View style={[styles.onlineDot, { backgroundColor: '#35B878' }]} />
            </Pressable>
          ))}
        </ScrollView>
      ) : null}

      <View style={[styles.infoCard, { backgroundColor: colors.secondary }]}>
        <View style={[styles.infoIcon, { backgroundColor: colors.card }]}>
          <Feather name="info" size={17} color={colors.primary} />
        </View>
        <View style={styles.flexOne}>
          <Text style={[styles.infoTitle, { color: colors.foreground }]}>Bantuan jarak jauh yang terpercaya</Text>
          <Text style={[styles.infoText, { color: colors.mutedForeground }]}>Setiap sesi harus disetujui oleh perangkat penerima.</Text>
        </View>
      </View>
    </ScrollView>
  );
}

function DevicesView({
  colors,
  devices,
  onSelect,
  onAdd,
}: {
  colors: ReturnType<typeof useColors>;
  devices: SavedDevice[];
  onSelect: (device: SavedDevice) => void;
  onAdd: (device: SavedDevice) => void;
}) {
  const [showAdd, setShowAdd] = useState(false);
  const [newId, setNewId] = useState('');
  const [newName, setNewName] = useState('');

  const addDevice = () => {
    if (newId.replace(/\s/g, '').length < 9 || !newName.trim()) return;
    onAdd({ id: newId, name: newName.trim(), model: 'Android', status: 'Online', lastSeen: 'Aktif sekarang' });
    setNewId('');
    setNewName('');
    setShowAdd(false);
  };

  return (
    <ScrollView contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
      <Header colors={colors} title="Perangkat saya" subtitle="TERHUBUNG DENGAN AMAN" />
      <View style={styles.devicePageIntro}>
        <Text style={[styles.sectionSubtitle, { color: colors.mutedForeground }]}>Kelola perangkat yang sering Anda akses.</Text>
        <Pressable accessibilityRole="button" onPress={() => setShowAdd((value) => !value)} style={({ pressed }) => [styles.addButton, { backgroundColor: colors.secondary }, pressed && styles.pressed]}>
          <Feather name={showAdd ? 'minus' : 'plus'} size={16} color={colors.primary} />
          <Text style={[styles.addButtonText, { color: colors.primary }]}>{showAdd ? 'Tutup' : 'Tambah'}</Text>
        </Pressable>
      </View>

      {showAdd ? (
        <View style={[styles.addDeviceCard, { backgroundColor: colors.card, borderColor: colors.border }]}>
          <Text style={[styles.fieldLabel, { color: colors.foreground }]}>Nama perangkat</Text>
          <TextInput value={newName} onChangeText={setNewName} placeholder="Contoh: HP kantor" placeholderTextColor={colors.mutedForeground} style={[styles.simpleInput, { color: colors.foreground, borderColor: colors.input, backgroundColor: colors.background }]} />
          <Text style={[styles.fieldLabel, { color: colors.foreground }]}>ID perangkat</Text>
          <TextInput value={newId} onChangeText={setNewId} keyboardType="number-pad" maxLength={11} placeholder="000 000 000" placeholderTextColor={colors.mutedForeground} style={[styles.simpleInput, { color: colors.foreground, borderColor: colors.input, backgroundColor: colors.background }]} />
          <PrimaryButton label="Simpan perangkat" onPress={addDevice} colors={colors} disabled={!newName.trim() || newId.replace(/\s/g, '').length < 9} />
        </View>
      ) : null}

      <View style={styles.deviceList}>
        {devices.map((device, index) => (
          <Pressable
            key={`${device.id}-${index}`}
            accessibilityRole="button"
            onPress={() => onSelect(device)}
            style={({ pressed }) => [styles.deviceRow, { backgroundColor: colors.card, borderColor: colors.border }, pressed && styles.pressed]}
          >
            <View style={[styles.deviceRowIcon, { backgroundColor: device.status === 'Online' ? colors.secondary : colors.muted }]}>
              <Feather name={device.name.includes('Pad') ? 'tablet' : 'smartphone'} size={20} color={device.status === 'Online' ? colors.primary : colors.mutedForeground} />
            </View>
            <View style={styles.flexOne}>
              <View style={styles.deviceNameLine}>
                <Text style={[styles.deviceRowName, { color: colors.foreground }]}>{device.name}</Text>
                <View style={[styles.statusPill, { backgroundColor: device.status === 'Online' ? '#EAF8F1' : colors.muted }]}>
                  <View style={[styles.statusPillDot, { backgroundColor: device.status === 'Online' ? '#35B878' : colors.mutedForeground }]} />
                  <Text style={[styles.statusPillText, { color: device.status === 'Online' ? '#23794F' : colors.mutedForeground }]}>{device.status}</Text>
                </View>
              </View>
              <Text style={[styles.deviceRowModel, { color: colors.mutedForeground }]}>{device.model} · {device.id}</Text>
              <Text style={[styles.deviceRowLastSeen, { color: colors.mutedForeground }]}>{device.lastSeen}</Text>
            </View>
            <Feather name="chevron-right" size={18} color={colors.mutedForeground} />
          </Pressable>
        ))}
      </View>
    </ScrollView>
  );
}

function ProfileView({
  colors,
  email,
  onLogout,
}: {
  colors: ReturnType<typeof useColors>;
  email: string;
  onLogout: () => void;
}) {
  const [accessibility, setAccessibility] = useState(true);
  const [screenShare, setScreenShare] = useState(true);
  const [notifications, setNotifications] = useState(false);

  const rows = [
    { label: 'Accessibility Service', description: 'Izinkan kontrol sentuhan saat remote', value: accessibility, onValueChange: setAccessibility, icon: 'command' as const },
    { label: 'Berbagi layar', description: 'Tampilkan layar saat menerima bantuan', value: screenShare, onValueChange: setScreenShare, icon: 'monitor' as const },
    { label: 'Notifikasi sesi', description: 'Dapatkan pemberitahuan saat sesi aktif', value: notifications, onValueChange: setNotifications, icon: 'bell' as const },
  ];

  return (
    <ScrollView contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
      <Header colors={colors} title="Pengaturan" subtitle="AKUN DAN KEAMANAN" />
      <View style={[styles.profileCard, { backgroundColor: colors.card, borderColor: colors.border }]}>
        <View style={[styles.avatar, { backgroundColor: colors.secondary }]}>
          <Text style={[styles.avatarText, { color: colors.primary }]}>{email.slice(0, 1).toUpperCase()}</Text>
        </View>
        <View style={styles.flexOne}>
          <Text style={[styles.profileName, { color: colors.foreground }]}>{email.split('@')[0]}</Text>
          <Text style={[styles.profileEmail, { color: colors.mutedForeground }]}>{email}</Text>
        </View>
        <View style={[styles.verifiedBadge, { backgroundColor: '#EAF8F1' }]}>
          <Feather name="check" size={13} color="#23794F" />
        </View>
      </View>

      <Text style={[styles.settingsHeading, { color: colors.foreground }]}>Izin perangkat</Text>
      <View style={[styles.settingsCard, { backgroundColor: colors.card, borderColor: colors.border }]}>
        {rows.map((row, index) => (
          <View key={row.label} style={[styles.settingRow, index < rows.length - 1 && { borderBottomColor: colors.border, borderBottomWidth: StyleSheet.hairlineWidth }]}>
            <View style={[styles.settingIcon, { backgroundColor: colors.secondary }]}>
              <Feather name={row.icon} size={17} color={colors.primary} />
            </View>
            <View style={styles.flexOne}>
              <Text style={[styles.settingLabel, { color: colors.foreground }]}>{row.label}</Text>
              <Text style={[styles.settingDescription, { color: colors.mutedForeground }]}>{row.description}</Text>
            </View>
            <Switch value={row.value} onValueChange={row.onValueChange} trackColor={{ false: colors.border, true: colors.primary }} thumbColor={colors.card} />
          </View>
        ))}
      </View>

      <Text style={[styles.settingsHeading, { color: colors.foreground }]}>Lainnya</Text>
      <View style={[styles.settingsCard, { backgroundColor: colors.card, borderColor: colors.border }]}>
        <Pressable accessibilityRole="button" onPress={() => undefined} style={({ pressed }) => [styles.settingRow, pressed && styles.pressed]}>
          <View style={[styles.settingIcon, { backgroundColor: colors.muted }]}><Feather name="help-circle" size={17} color={colors.mutedForeground} /></View>
          <View style={styles.flexOne}><Text style={[styles.settingLabel, { color: colors.foreground }]}>Pusat bantuan</Text><Text style={[styles.settingDescription, { color: colors.mutedForeground }]}>Panduan menggunakan LinkDroid</Text></View>
          <Feather name="chevron-right" size={18} color={colors.mutedForeground} />
        </Pressable>
        <Pressable accessibilityRole="button" onPress={onLogout} style={({ pressed }) => [styles.settingRow, { borderTopColor: colors.border, borderTopWidth: StyleSheet.hairlineWidth }, pressed && styles.pressed]}>
          <View style={[styles.settingIcon, { backgroundColor: '#FFF0F2' }]}><Feather name="log-out" size={17} color={colors.destructive} /></View>
          <Text style={[styles.settingLabel, { color: colors.destructive }]}>Keluar dari akun</Text>
        </Pressable>
      </View>
      <Text style={[styles.versionText, { color: colors.mutedForeground }]}>LinkDroid v1.0.0</Text>
    </ScrollView>
  );
}

function SessionView({
  colors,
  device,
  onEnd,
}: {
  colors: ReturnType<typeof useColors>;
  device: SavedDevice;
  onEnd: () => void;
}) {
  const [muted, setMuted] = useState(false);
  const [touchMode, setTouchMode] = useState(true);
  const [showTools, setShowTools] = useState(false);

  return (
    <View style={[styles.sessionScreen, { backgroundColor: '#111827' }]}>
      <View style={styles.sessionHeader}>
        <View style={styles.sessionHeaderLeft}>
          <IconButton name="x" color="#FFFFFF" accessibilityLabel="Tutup sesi" onPress={onEnd} />
          <View>
            <Text style={styles.sessionDeviceName}>{device.name}</Text>
            <View style={styles.sessionStatusLine}><View style={styles.liveDot} /><Text style={styles.sessionStatusText}>Terhubung · {device.id}</Text></View>
          </View>
        </View>
        <IconButton name="more-horizontal" color="#FFFFFF" accessibilityLabel="Opsi sesi" onPress={() => setShowTools((value) => !value)} />
      </View>
      <View style={styles.remoteStage}>
        <View style={styles.remotePhone}>
          <View style={styles.remoteNotch} />
          <View style={styles.remoteStatusBar}><Text style={styles.remoteTinyText}>09:41</Text><View style={styles.remoteSignal}><View /><View /><View /></View></View>
          <View style={styles.remoteWallpaper}>
            <Text style={styles.remoteClock}>10:30</Text>
            <Text style={styles.remoteDate}>Sab, 24 Mei · 28°C</Text>
            <View style={styles.remoteSearch}><Feather name="search" size={13} color="#70809B" /><Text style={styles.remoteSearchText}>Cari di perangkat</Text></View>
            <View style={styles.remoteAppGrid}>
              {['grid', 'chrome', 'settings', 'folder', 'youtube', 'camera'].map((icon) => <View key={icon} style={styles.remoteApp}><Feather name={icon as React.ComponentProps<typeof Feather>['name']} size={18} color="#FFFFFF" /></View>)}
            </View>
            <View style={styles.remoteDock}><Feather name="phone" size={18} color="#FFFFFF" /><Feather name="message-circle" size={18} color="#FFFFFF" /><Feather name="user" size={18} color="#FFFFFF" /><Feather name="image" size={18} color="#FFFFFF" /></View>
          </View>
        </View>
        <View style={styles.remotePointer}><Feather name="mouse-pointer" size={15} color="#FFFFFF" /></View>
      </View>
      <View style={styles.sessionBottom}>
        {showTools ? <View style={styles.toolsPopover}><Text style={styles.toolsTitle}>Alat sesi</Text><Text style={styles.toolsText}>Kualitas: Otomatis</Text><Text style={styles.toolsText}>Enkripsi: Aktif</Text></View> : null}
        <View style={styles.sessionToolbar}>
          <Pressable accessibilityRole="button" onPress={() => setTouchMode((value) => !value)} style={[styles.sessionTool, touchMode && styles.sessionToolActive]}><Feather name="move" size={19} color={touchMode ? '#1F65E8' : '#FFFFFF'} /><Text style={styles.sessionToolText}>Kontrol</Text></Pressable>
          <Pressable accessibilityRole="button" onPress={() => setMuted((value) => !value)} style={styles.sessionTool}><Feather name={muted ? 'mic-off' : 'mic'} size={19} color="#FFFFFF" /><Text style={styles.sessionToolText}>{muted ? 'Bisu' : 'Audio'}</Text></Pressable>
          <Pressable accessibilityRole="button" onPress={() => undefined} style={styles.sessionTool}><Feather name="maximize" size={19} color="#FFFFFF" /><Text style={styles.sessionToolText}>Layar</Text></Pressable>
          <Pressable accessibilityRole="button" onPress={onEnd} style={[styles.sessionTool, styles.endSessionTool]}><Feather name="phone-off" size={19} color="#FFFFFF" /><Text style={styles.sessionToolText}>Akhiri</Text></Pressable>
        </View>
        <Text style={styles.sessionEncrypted}><Feather name="lock" size={11} color="#9CAAC0" /> Sesi terenkripsi end-to-end</Text>
      </View>
    </View>
  );
}

function BottomNav({
  colors,
  active,
  onChange,
}: {
  colors: ReturnType<typeof useColors>;
  active: 'home' | 'devices' | 'profile';
  onChange: (view: 'home' | 'devices' | 'profile') => void;
}) {
  const items = [
    { key: 'home' as const, label: 'Beranda', icon: 'home' as const },
    { key: 'devices' as const, label: 'Perangkat', icon: 'smartphone' as const },
    { key: 'profile' as const, label: 'Pengaturan', icon: 'settings' as const },
  ];
  return (
    <View style={[styles.bottomNav, { backgroundColor: colors.card, borderTopColor: colors.border }]}>
      {items.map((item) => {
        const selected = active === item.key;
        return (
          <Pressable key={item.key} accessibilityRole="tab" accessibilityState={{ selected }} onPress={() => onChange(item.key)} style={({ pressed }) => [styles.navItem, pressed && styles.pressed]}>
            <View style={[styles.navIcon, selected && { backgroundColor: colors.secondary }]}>
              <Feather name={item.icon} size={19} color={selected ? colors.primary : colors.mutedForeground} />
            </View>
            <Text style={[styles.navLabel, { color: selected ? colors.primary : colors.mutedForeground }]}>{item.label}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

export default function IndexScreen() {
  const colors = useColors();
  const insets = useSafeAreaInsets();
  const [view, setView] = useState<AppView>('login');
  const [email, setEmail] = useState('');
  const [notice, setNotice] = useState<Notice>(null);
  const [devices, setDevices] = useState<SavedDevice[]>(INITIAL_DEVICES);
  const [sessionDevice, setSessionDevice] = useState<SavedDevice | null>(null);

  useEffect(() => {
    void (async () => {
      const [savedSession, savedDevices] = await Promise.all([
        AsyncStorage.getItem('linkdroid-session'),
        AsyncStorage.getItem('linkdroid-devices'),
      ]);
      if (savedSession) {
        setEmail(savedSession);
        setView('home');
      }
      if (savedDevices) {
        try {
          setDevices(JSON.parse(savedDevices) as SavedDevice[]);
        } catch {
          setDevices(INITIAL_DEVICES);
        }
      }
    })();
  }, []);

  const copyId = async () => {
    if (typeof navigator !== 'undefined' && navigator.clipboard) {
      await navigator.clipboard.writeText(DEVICE_ID);
    }
    await Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
    setNotice({ tone: 'success', message: 'ID perangkat siap dibagikan.' });
  };

  const login = async (nextEmail: string) => {
    setEmail(nextEmail);
    await AsyncStorage.setItem('linkdroid-session', nextEmail);
    setView('home');
    setNotice({ tone: 'success', message: 'Selamat datang di LinkDroid.' });
  };

  const connect = (id: string) => {
    const normalized = id.replace(/\s/g, '');
    const found = devices.find((device) => device.id.replace(/\s/g, '') === normalized);
    const target: SavedDevice = found ?? { id, name: 'Perangkat tujuan', model: 'Android', status: 'Online', lastSeen: 'Baru saja' };
    setSessionDevice(target);
    setView('session');
    void Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
  };

  const selectDevice = (device: SavedDevice) => {
    if (device.status === 'Offline') {
      setNotice({ tone: 'error', message: `${device.name} sedang offline.` });
      return;
    }
    connect(device.id);
  };

  const addDevice = (device: SavedDevice) => {
    const next = [device, ...devices];
    setDevices(next);
    void AsyncStorage.setItem('linkdroid-devices', JSON.stringify(next));
    setNotice({ tone: 'success', message: `${device.name} ditambahkan ke perangkat Anda.` });
  };

  const logout = async () => {
    await AsyncStorage.removeItem('linkdroid-session');
    setView('login');
    setEmail('');
    setNotice(null);
  };

  const content = useMemo(() => {
    if (view === 'login') return <LoginView colors={colors} insets={insets} onLogin={login} />;
    if (view === 'session' && sessionDevice) return <SessionView colors={colors} device={sessionDevice} onEnd={() => { setSessionDevice(null); setView('home'); setNotice({ tone: 'success', message: 'Sesi remote telah diakhiri.' }); }} />;
    if (view === 'devices') return <DevicesView colors={colors} devices={devices} onSelect={selectDevice} onAdd={addDevice} />;
    if (view === 'profile') return <ProfileView colors={colors} email={email} onLogout={() => void logout()} />;
    return <HomeView colors={colors} email={email} devices={devices} onConnect={connect} onCopy={() => void copyId()} onOpenSettings={() => setView('profile')} onDevice={selectDevice} />;
  }, [colors, devices, email, insets, sessionDevice, view]);

  if (view === 'login' || view === 'session') return <View style={{ flex: 1, backgroundColor: view === 'session' ? '#111827' : colors.background }}>{content}</View>;

  return (
    <View style={[styles.appShell, { backgroundColor: colors.background }]}>
      <View style={{ flex: 1, paddingTop: insets.top }}>{content}</View>
      <NoticeBanner notice={notice} colors={colors} onDismiss={() => setNotice(null)} />
      <BottomNav colors={colors} active={view as 'home' | 'devices' | 'profile'} onChange={setView} />
      <View style={{ height: insets.bottom, backgroundColor: colors.card }} />
    </View>
  );
}

const styles = StyleSheet.create({
  appShell: { flex: 1 },
  flexOne: { flex: 1 },
  pressed: { opacity: 0.75 },
  loginContent: { flexGrow: 1, paddingHorizontal: 22, justifyContent: 'center' },
  loginBrand: { alignItems: 'center', marginBottom: 34 },
  brandMark: { width: 76, height: 76, borderRadius: 24, alignItems: 'center', justifyContent: 'center', marginBottom: 17, shadowColor: '#1F65E8', shadowOpacity: 0.18, shadowRadius: 18, shadowOffset: { width: 0, height: 8 }, elevation: 5 },
  brandMarkSmall: { width: 38, height: 38, borderRadius: 12, marginBottom: 0, shadowOpacity: 0, elevation: 0 },
  brandName: { fontSize: 27, fontWeight: '700', letterSpacing: -0.8 },
  brandTagline: { fontSize: 13, marginTop: 6 },
  loginCard: { borderWidth: 1, borderRadius: 24, padding: 22, shadowColor: '#14213D', shadowOpacity: 0.05, shadowRadius: 18, shadowOffset: { width: 0, height: 6 }, elevation: 2 },
  loginTitle: { fontSize: 21, fontWeight: '700', letterSpacing: -0.3 },
  loginSubtitle: { fontSize: 13, marginTop: 7, marginBottom: 24, lineHeight: 19 },
  formGap: { gap: 9 },
  fieldLabel: { fontSize: 12, fontWeight: '600', marginTop: 3, marginBottom: 1 },
  inputWrap: { minHeight: 52, borderRadius: 14, borderWidth: 1, flexDirection: 'row', alignItems: 'center', paddingHorizontal: 14 },
  textInput: { flex: 1, fontSize: 14, paddingHorizontal: 11, paddingVertical: 13 },
  errorText: { fontSize: 12, marginTop: 9, lineHeight: 17 },
  primaryButton: { minHeight: 50, borderRadius: 14, alignItems: 'center', justifyContent: 'center', flexDirection: 'row', gap: 8, marginTop: 18 },
  primaryButtonText: { fontSize: 14, fontWeight: '700' },
  iconButton: { width: 34, height: 34, borderRadius: 17, alignItems: 'center', justifyContent: 'center' },
  demoButton: { minHeight: 42, alignItems: 'center', justifyContent: 'center', flexDirection: 'row', gap: 7, marginTop: 8 },
  demoButtonText: { fontSize: 13, fontWeight: '600' },
  loadingIndicator: { position: 'absolute', right: 37, bottom: 87 },
  loginFooter: { flexDirection: 'row', justifyContent: 'center', gap: 4, marginTop: 20 },
  footerText: { fontSize: 12 },
  footerLink: { fontSize: 12, fontWeight: '700' },
  versionText: { textAlign: 'center', fontSize: 11, marginTop: 22 },
  header: { minHeight: 75, paddingHorizontal: 21, paddingTop: 12, paddingBottom: 10, flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  eyebrow: { fontSize: 10, fontWeight: '700', letterSpacing: 1.1, marginBottom: 5 },
  screenTitle: { fontSize: 26, fontWeight: '700', letterSpacing: -0.6 },
  scrollContent: { paddingHorizontal: 20, paddingBottom: 28 },
  deviceIdCard: { borderRadius: 22, padding: 20, overflow: 'hidden', marginTop: 8, minHeight: 170 },
  deviceIdGlow: { position: 'absolute', width: 190, height: 190, borderRadius: 95, right: -58, top: -72, backgroundColor: 'rgba(255,255,255,0.09)' },
  deviceIdTop: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  deviceIdLabelRow: { flexDirection: 'row', alignItems: 'center', gap: 7 },
  liveDot: { width: 7, height: 7, borderRadius: 4, backgroundColor: '#69E09C' },
  deviceIdLabel: { color: 'rgba(255,255,255,0.78)', fontSize: 10, fontWeight: '700', letterSpacing: 1 },
  deviceId: { color: '#FFFFFF', fontSize: 34, fontWeight: '700', letterSpacing: 1.2, marginTop: 19 },
  deviceIdBottom: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: 18 },
  deviceIdHint: { color: 'rgba(255,255,255,0.72)', fontSize: 11, flex: 1 },
  copyButton: { backgroundColor: '#FFFFFF', borderRadius: 10, paddingHorizontal: 11, paddingVertical: 8, flexDirection: 'row', alignItems: 'center', gap: 6 },
  copyButtonText: { fontSize: 11, fontWeight: '700' },
  sectionHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-end', marginTop: 27, marginBottom: 12 },
  sectionTitle: { fontSize: 16, fontWeight: '700', letterSpacing: -0.2 },
  sectionSubtitle: { fontSize: 12, marginTop: 4 },
  secureBadge: { borderRadius: 8, paddingHorizontal: 8, paddingVertical: 6, flexDirection: 'row', alignItems: 'center', gap: 4 },
  secureBadgeText: { fontSize: 10, fontWeight: '700' },
  connectCard: { borderWidth: 1, borderRadius: 18, padding: 16 },
  connectInputWrap: { minHeight: 50, borderWidth: 1, borderRadius: 13, flexDirection: 'row', alignItems: 'center', paddingHorizontal: 13, marginTop: 8 },
  connectInput: { flex: 1, fontSize: 18, fontWeight: '600', letterSpacing: 1, paddingHorizontal: 11, paddingVertical: 12 },
  viewAll: { fontSize: 12, fontWeight: '600' },
  deviceChips: { gap: 10, paddingBottom: 3 },
  deviceChip: { width: 190, minHeight: 68, borderWidth: 1, borderRadius: 16, padding: 10, flexDirection: 'row', alignItems: 'center', gap: 9 },
  deviceChipIcon: { width: 35, height: 35, borderRadius: 11, alignItems: 'center', justifyContent: 'center' },
  deviceChipName: { fontSize: 12, fontWeight: '700' },
  deviceChipId: { fontSize: 10, marginTop: 4 },
  onlineDot: { width: 7, height: 7, borderRadius: 4 },
  infoCard: { flexDirection: 'row', gap: 11, padding: 13, borderRadius: 15, marginTop: 22, alignItems: 'flex-start' },
  infoIcon: { width: 30, height: 30, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
  infoTitle: { fontSize: 12, fontWeight: '700' },
  infoText: { fontSize: 11, lineHeight: 16, marginTop: 3 },
  bottomNav: { minHeight: 67, borderTopWidth: StyleSheet.hairlineWidth, flexDirection: 'row', paddingHorizontal: 18, paddingTop: 8 },
  navItem: { flex: 1, alignItems: 'center', gap: 2 },
  navIcon: { width: 38, height: 29, borderRadius: 11, alignItems: 'center', justifyContent: 'center' },
  navLabel: { fontSize: 10, fontWeight: '600' },
  notice: { position: 'absolute', left: 16, right: 16, bottom: 74, minHeight: 47, borderRadius: 14, borderWidth: 1, flexDirection: 'row', alignItems: 'center', paddingHorizontal: 13, gap: 9, zIndex: 2, shadowColor: '#13213C', shadowOpacity: 0.08, shadowRadius: 14, shadowOffset: { width: 0, height: 5 }, elevation: 3 },
  noticeText: { flex: 1, fontSize: 12, fontWeight: '600' },
  devicePageIntro: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 15 },
  addButton: { borderRadius: 10, paddingHorizontal: 10, paddingVertical: 8, flexDirection: 'row', alignItems: 'center', gap: 5 },
  addButtonText: { fontSize: 11, fontWeight: '700' },
  addDeviceCard: { borderWidth: 1, borderRadius: 18, padding: 15, gap: 9, marginBottom: 15 },
  simpleInput: { borderWidth: 1, borderRadius: 12, minHeight: 46, paddingHorizontal: 12, fontSize: 13 },
  deviceList: { gap: 10 },
  deviceRow: { borderWidth: 1, borderRadius: 17, padding: 13, flexDirection: 'row', alignItems: 'center', gap: 11 },
  deviceRowIcon: { width: 43, height: 43, borderRadius: 14, alignItems: 'center', justifyContent: 'center' },
  deviceNameLine: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  deviceRowName: { fontSize: 14, fontWeight: '700' },
  statusPill: { borderRadius: 7, paddingHorizontal: 6, paddingVertical: 3, flexDirection: 'row', alignItems: 'center', gap: 4 },
  statusPillDot: { width: 5, height: 5, borderRadius: 3 },
  statusPillText: { fontSize: 9, fontWeight: '700' },
  deviceRowModel: { fontSize: 10, marginTop: 5 },
  deviceRowLastSeen: { fontSize: 10, marginTop: 3 },
  profileCard: { borderWidth: 1, borderRadius: 19, padding: 15, flexDirection: 'row', alignItems: 'center', gap: 12, marginTop: 8 },
  avatar: { width: 47, height: 47, borderRadius: 17, alignItems: 'center', justifyContent: 'center' },
  avatarText: { fontSize: 20, fontWeight: '700' },
  profileName: { fontSize: 15, fontWeight: '700' },
  profileEmail: { fontSize: 11, marginTop: 4 },
  verifiedBadge: { width: 25, height: 25, borderRadius: 13, alignItems: 'center', justifyContent: 'center' },
  settingsHeading: { fontSize: 13, fontWeight: '700', marginTop: 25, marginBottom: 9 },
  settingsCard: { borderWidth: 1, borderRadius: 18, overflow: 'hidden' },
  settingRow: { minHeight: 68, paddingHorizontal: 13, paddingVertical: 10, flexDirection: 'row', alignItems: 'center', gap: 11 },
  settingIcon: { width: 34, height: 34, borderRadius: 11, alignItems: 'center', justifyContent: 'center' },
  settingLabel: { fontSize: 12, fontWeight: '600' },
  settingDescription: { fontSize: 10, marginTop: 4, lineHeight: 14 },
  sessionScreen: { flex: 1 },
  sessionHeader: { paddingTop: 14, paddingHorizontal: 12, minHeight: 67, flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  sessionHeaderLeft: { flexDirection: 'row', alignItems: 'center', gap: 7 },
  sessionDeviceName: { color: '#FFFFFF', fontSize: 14, fontWeight: '700' },
  sessionStatusLine: { flexDirection: 'row', alignItems: 'center', gap: 5, marginTop: 4 },
  sessionStatusText: { color: '#9CAAC0', fontSize: 10 },
  remoteStage: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  remotePhone: { width: 220, height: 416, borderRadius: 27, backgroundColor: '#25355C', borderWidth: 4, borderColor: '#465B87', overflow: 'hidden', shadowColor: '#000000', shadowOpacity: 0.3, shadowRadius: 22, shadowOffset: { width: 0, height: 10 }, elevation: 10 },
  remoteNotch: { width: 75, height: 17, borderBottomLeftRadius: 12, borderBottomRightRadius: 12, backgroundColor: '#0B1224', alignSelf: 'center', zIndex: 2 },
  remoteStatusBar: { position: 'absolute', top: 5, left: 13, right: 13, flexDirection: 'row', justifyContent: 'space-between', zIndex: 3 },
  remoteTinyText: { fontSize: 7, color: '#FFFFFF', fontWeight: '600' },
  remoteSignal: { flexDirection: 'row', gap: 2, alignItems: 'flex-end' },
  remoteWallpaper: { flex: 1, padding: 16, paddingTop: 45, backgroundColor: '#305D9D', alignItems: 'center' },
  remoteClock: { color: '#FFFFFF', fontSize: 30, fontWeight: '300' },
  remoteDate: { color: 'rgba(255,255,255,0.82)', fontSize: 9, marginTop: 2 },
  remoteSearch: { height: 28, alignSelf: 'stretch', backgroundColor: 'rgba(255,255,255,0.9)', borderRadius: 14, marginTop: 22, flexDirection: 'row', alignItems: 'center', paddingHorizontal: 9, gap: 6 },
  remoteSearchText: { color: '#70809B', fontSize: 8 },
  remoteAppGrid: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'center', gap: 12, marginTop: 35, width: 150 },
  remoteApp: { width: 32, height: 32, borderRadius: 9, alignItems: 'center', justifyContent: 'center', backgroundColor: 'rgba(255,255,255,0.22)' },
  remoteDock: { position: 'absolute', bottom: 14, left: 15, right: 15, height: 43, borderRadius: 14, backgroundColor: 'rgba(255,255,255,0.2)', flexDirection: 'row', justifyContent: 'space-around', alignItems: 'center' },
  remotePointer: { position: 'absolute', width: 29, height: 29, borderRadius: 15, backgroundColor: '#1F65E8', borderWidth: 2, borderColor: '#FFFFFF', alignItems: 'center', justifyContent: 'center', marginLeft: 150, marginTop: 60 },
  sessionBottom: { minHeight: 128, paddingBottom: 14, paddingHorizontal: 18, alignItems: 'center' },
  sessionToolbar: { width: '100%', maxWidth: 390, minHeight: 66, borderRadius: 20, backgroundColor: '#1F293F', flexDirection: 'row', justifyContent: 'space-around', alignItems: 'center', paddingHorizontal: 5 },
  sessionTool: { minWidth: 58, minHeight: 52, alignItems: 'center', justifyContent: 'center', gap: 4, borderRadius: 14 },
  sessionToolActive: { backgroundColor: '#FFFFFF' },
  sessionToolText: { color: '#FFFFFF', fontSize: 9, fontWeight: '600' },
  endSessionTool: { backgroundColor: '#D94B5B' },
  sessionEncrypted: { color: '#9CAAC0', fontSize: 10, marginTop: 12 },
  toolsPopover: { position: 'absolute', bottom: 138, right: 18, width: 165, backgroundColor: '#1F293F', borderRadius: 14, padding: 14, zIndex: 3 },
  toolsTitle: { color: '#FFFFFF', fontSize: 12, fontWeight: '700', marginBottom: 8 },
  toolsText: { color: '#B7C2D4', fontSize: 11, marginTop: 6 },
});