export function frame(identity = '010203040506', role = 0) {
  const data = [0x55, 0xaa, 0, 0x30, 9, 0, role, ...identity.match(/../g).map(x => parseInt(x, 16)), 0];
  data.push(data.reduce((a, b) => a + b, 0) % 256, 0xfe, 0xfd);
  return data.map(x => x.toString(16).padStart(2, '0')).join(' ');
}
export const row = (identity, role = 0, stamp = '1800000000.125') => `${stamp} 100 100 D com.navee.ucaret: Send authData: ${frame(identity, role)}`;
