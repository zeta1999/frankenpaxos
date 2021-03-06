package frankenpaxos.fastpaxos

case class Config[Transport <: frankenpaxos.Transport[Transport]](
    f: Int,
    leaderAddresses: Seq[Transport#Address],
    acceptorAddresses: Seq[Transport#Address]
) {
  def n: Int = (2 * f) + 1

  def classicQuorumSize = f + 1

  def quorumMajoritySize = ((f + 1).toDouble / 2).floor.toInt + 1

  def fastQuorumSize = f + quorumMajoritySize

  def valid(): Boolean = {
    return (leaderAddresses.size >= f + 1) && (acceptorAddresses.size == n)
  }
}
