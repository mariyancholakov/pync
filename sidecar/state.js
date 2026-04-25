module.exports = {
  role: null,
  topicKey: null,
  secrets: [],
  peerCount: 0,
  connected: false,

  setReady(role, topicKey) {
    this.role = role;
    this.topicKey = topicKey;
    this.connected = true;
  },

  setSecrets(secrets) {
    this.secrets = secrets;
  },

  setPeerCount(count) {
    this.peerCount = count;
  },

  reset() {
    this.role = null;
    this.topicKey = null;
    this.secrets = [];
    this.peerCount = 0;
    this.connected = false;
  }
};
