import { RefreshCwIcon } from 'lucide-react'
import type { IsoItem, IsoKind, Listing } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { formatBytes } from '@/lib/format'
import { isThreeK3y } from '@/lib/gate'

const KIND_LABEL: Record<IsoKind, string> = {
  '3k3y-encrypted': '3k3y · encrypted',
  '3k3y-decrypted': '3k3y · decrypted',
  standard: 'Standard',
  invalid: 'Invalid',
}

function KindBadge({ kind }: { kind: IsoKind }) {
  const variant = kind === 'invalid' ? 'destructive' : kind === 'standard' ? 'outline' : 'secondary'
  return <Badge variant={variant}>{KIND_LABEL[kind]}</Badge>
}

function keyCell(iso: IsoItem) {
  if (!iso.valid) return <span className="text-muted-foreground">—</span>
  if (isThreeK3y(iso)) return <span className="text-muted-foreground">not needed</span>
  if (iso.suggested_key) return <span>{iso.suggested_key}</span>
  return <span className="text-muted-foreground">no matching key</span>
}

interface Props {
  listing: Listing<IsoItem> | undefined
  selected: string | null
  onSelect: (name: string) => void
  onRefresh: () => void
  refreshing?: boolean
  disabled?: boolean
}

export function IsoTable({ listing, selected, onSelect, onRefresh, refreshing, disabled }: Props) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Disc images</CardTitle>
        <CardDescription>ISO files found in the input folder{listing ? ` (${listing.folder})` : ''}.</CardDescription>
        <CardAction>
          <Button variant="outline" size="sm" onClick={onRefresh} disabled={refreshing}>
            <RefreshCwIcon data-icon="inline-start" />
            Refresh
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent>
        {!listing ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : !listing.available ? (
          <p className="text-sm text-muted-foreground">
            The ISO folder <code className="font-mono">{listing.folder}</code> is not available. Mount your ISO
            folder at that path.
          </p>
        ) : listing.items.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            No <code className="font-mono">.iso</code> files found in <code className="font-mono">{listing.folder}</code>.
          </p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-8" />
                <TableHead>Name</TableHead>
                <TableHead className="text-right">Size</TableHead>
                <TableHead>Type</TableHead>
                <TableHead>Key</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {listing.items.map((iso) => {
                const id = `iso-${iso.name}`
                return (
                  <TableRow key={iso.name} data-state={selected === iso.name ? 'selected' : undefined}>
                    <TableCell>
                      <input
                        type="radio"
                        name="iso"
                        id={id}
                        className="accent-primary"
                        checked={selected === iso.name}
                        disabled={!iso.valid || disabled}
                        onChange={() => onSelect(iso.name)}
                      />
                    </TableCell>
                    <TableCell className="whitespace-normal">
                      <label htmlFor={id} className={iso.valid ? 'cursor-pointer' : 'text-muted-foreground'}>
                        {iso.name}
                      </label>
                      {!iso.valid && iso.reason && (
                        <p className="text-xs text-destructive">{iso.reason}</p>
                      )}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{formatBytes(iso.size)}</TableCell>
                    <TableCell>
                      <KindBadge kind={iso.kind} />
                    </TableCell>
                    <TableCell>{keyCell(iso)}</TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  )
}
